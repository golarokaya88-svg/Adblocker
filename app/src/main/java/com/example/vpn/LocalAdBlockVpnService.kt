package com.example.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.blocker.AdBlocklistEngine
import com.example.data.ShieldStatsRepository
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class LocalAdBlockVpnService : VpnService() {

  companion object {
    const val ACTION_START = "com.example.vpn.ACTION_START"
    const val ACTION_STOP = "com.example.vpn.ACTION_STOP"

    private const val TAG = "VoidShieldVpn"
    private const val NOTIFICATION_CHANNEL_ID = "voidshield_vpn_channel"
    private const val NOTIFICATION_ID = 1001
    private const val MTU = 1500
    private const val VPN_ADDRESS = "10.8.0.1"
    private const val VIRTUAL_DNS_PRIMARY = "10.8.0.2"
    private const val VIRTUAL_DNS_SECONDARY = "10.8.0.3"
    private const val UPSTREAM_DNS_PRIMARY = "1.1.1.1"
    private const val UPSTREAM_DNS_SECONDARY = "8.8.8.8"
    private const val DNS_PORT = 53
    private const val UPSTREAM_TIMEOUT_MS = 2500
    private const val MAX_INFLIGHT_QUERIES = 64
  }

  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val writeLock = Any()
  private val pendingFlushCount = AtomicLong(0L)
  private val upstreamSemaphore = Semaphore(MAX_INFLIGHT_QUERIES)

  private var tunInterface: ParcelFileDescriptor? = null
  private var packetLoopJob: Job? = null
  private var flushJob: Job? = null
  private var lastNotificationUpdateMs = 0L

  private lateinit var blocklistEngine: AdBlocklistEngine
  private lateinit var statsRepository: ShieldStatsRepository

  override fun onCreate() {
    super.onCreate()
    blocklistEngine = AdBlocklistEngine.fromContext(applicationContext)
    statsRepository = ShieldStatsRepository(applicationContext)
    ensureNotificationChannel()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    return when (intent?.action) {
      ACTION_STOP -> {
        stopVpn()
        START_NOT_STICKY
      }
      ACTION_START, null -> {
        startVpn()
        START_STICKY
      }
      else -> START_STICKY
    }
  }

  override fun onRevoke() {
    stopVpn()
    super.onRevoke()
  }

  override fun onDestroy() {
    stopVpnInternal()
    serviceScope.cancel()
    super.onDestroy()
  }

  private fun startVpn() {
    if (tunInterface != null && packetLoopJob?.isActive == true) {
      VpnRuntimeState.setStatus(ProtectionStatus.Active)
      return
    }

    VpnRuntimeState.setStatus(ProtectionStatus.Connecting)
    VpnRuntimeState.resetSession()
    startForegroundCompat(buildNotification(0L))

    try {
      val builder =
        Builder()
          .setSession(getString(R.string.app_name))
          .addAddress(VPN_ADDRESS, 32)
          .addDnsServer(VIRTUAL_DNS_PRIMARY)
          .addRoute(VIRTUAL_DNS_PRIMARY, 32)
          .addDnsServer(VIRTUAL_DNS_SECONDARY)
          .addRoute(VIRTUAL_DNS_SECONDARY, 32)
          .setMtu(MTU)
          .setConfigureIntent(mainActivityPendingIntent())

      try {
        builder.addDisallowedApplication(packageName)
      } catch (_: PackageManager.NameNotFoundException) {
        // Ignored
      }

      val established = builder.establish()
      if (established == null) {
        VpnRuntimeState.setStatus(ProtectionStatus.Error(getString(R.string.vpn_permission_denied)))
        stopSelfSafe()
        return
      }

      tunInterface = established
      VpnRuntimeState.setStatus(ProtectionStatus.Active)
      startFlushTicker()
      startPacketLoop(established)
    } catch (e: Exception) {
      Log.e(TAG, "Failed to establish local VPN", e)
      VpnRuntimeState.setStatus(
        ProtectionStatus.Error(e.localizedMessage ?: getString(R.string.status_error))
      )
      stopVpnInternal()
      stopSelf()
    }
  }

  private fun startPacketLoop(tunFd: ParcelFileDescriptor) {
    packetLoopJob?.cancel()
    packetLoopJob =
      serviceScope.launch {
        val input = FileInputStream(tunFd.fileDescriptor)
        val output = FileOutputStream(tunFd.fileDescriptor)
        val buffer = ByteArray(MTU)

        try {
          while (isActive) {
            val bytesRead = input.read(buffer)
            if (bytesRead <= 0) {
              if (bytesRead < 0) break
              continue
            }

            val query = DnsPacketProcessor.parseDnsQueryPacket(buffer, bytesRead) ?: continue
            if (blocklistEngine.isBlocked(query.domain)) {
              val blockedResponse = DnsPacketProcessor.buildBlockedDnsResponse(query)
              writePacketSafe(output, blockedResponse)
              onDomainBlocked()
            } else {
              launch {
                upstreamSemaphore.withPermit {
                  val upstreamReply = forwardDnsQuery(query.dnsPayload)
                  if (upstreamReply != null && isActive) {
                    val responsePacket =
                      DnsPacketProcessor.buildForwardedDnsResponse(query, upstreamReply)
                    writePacketSafe(output, responsePacket)
                  }
                }
              }
            }
          }
        } catch (e: CancellationException) {
          throw e
        } catch (e: IOException) {
          if (isActive) {
            Log.w(TAG, "TUN read loop closed", e)
          }
        }
      }
  }

  private fun writePacketSafe(output: FileOutputStream, packet: ByteArray) {
    synchronized(writeLock) {
      try {
        output.write(packet)
      } catch (_: IOException) {
        // Tunnel closed while writing response
      }
    }
  }

  private fun forwardDnsQuery(dnsPayload: ByteArray): ByteArray? {
    return queryUpstream(UPSTREAM_DNS_PRIMARY, dnsPayload)
      ?: queryUpstream(UPSTREAM_DNS_SECONDARY, dnsPayload)
  }

  private fun queryUpstream(serverIp: String, dnsPayload: ByteArray): ByteArray? {
    var socket: DatagramSocket? = null
    return try {
      socket = DatagramSocket()
      if (!protect(socket)) {
        Log.w(TAG, "Failed to protect upstream UDP socket")
        return null
      }
      socket.soTimeout = UPSTREAM_TIMEOUT_MS
      val address = InetAddress.getByName(serverIp)
      val requestPacket = DatagramPacket(dnsPayload, dnsPayload.size, address, DNS_PORT)
      socket.send(requestPacket)

      val responseBuffer = ByteArray(MTU)
      val responsePacket = DatagramPacket(responseBuffer, responseBuffer.size)
      socket.receive(responsePacket)
      responseBuffer.copyOf(responsePacket.length)
    } catch (_: IOException) {
      null
    } finally {
      socket?.close()
    }
  }

  private fun onDomainBlocked() {
    val sessionTotal = VpnRuntimeState.incrementSessionBlocked(1L)
    pendingFlushCount.incrementAndGet()

    val now = System.currentTimeMillis()
    if (now - lastNotificationUpdateMs >= 2000L) {
      lastNotificationUpdateMs = now
      val manager = getSystemService(NotificationManager::class.java)
      manager?.notify(NOTIFICATION_ID, buildNotification(sessionTotal))
    }
  }

  private fun startFlushTicker() {
    flushJob?.cancel()
    flushJob =
      serviceScope.launch {
        while (isActive) {
          delay(600L)
          flushPendingBlockedCount()
        }
      }
  }

  private suspend fun flushPendingBlockedCount() {
    val delta = pendingFlushCount.getAndSet(0L)
    if (delta > 0L) {
      statsRepository.incrementTotalBlocked(delta)
    }
  }

  private fun stopVpn() {
    stopVpnInternal()
    stopSelfSafe()
  }

  private fun stopVpnInternal() {
    packetLoopJob?.cancel()
    packetLoopJob = null

    flushJob?.cancel()
    flushJob = null

    val remaining = pendingFlushCount.getAndSet(0L)
    if (remaining > 0L) {
      runBlocking(Dispatchers.IO) { statsRepository.incrementTotalBlocked(remaining) }
    }

    try {
      tunInterface?.close()
    } catch (_: IOException) {
      // Ignore close failure
    }
    tunInterface = null

    VpnRuntimeState.resetSession()
    VpnRuntimeState.setStatus(ProtectionStatus.Idle)
  }

  private fun stopSelfSafe() {
    stopForeground(STOP_FOREGROUND_REMOVE)
    stopSelf()
  }

  private fun startForegroundCompat(notification: Notification) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      startForeground(
        NOTIFICATION_ID,
        notification,
        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
      )
    } else {
      startForeground(NOTIFICATION_ID, notification)
    }
  }

  private fun ensureNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val manager = getSystemService(NotificationManager::class.java) ?: return
      val channel =
        NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
          )
          .apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(false)
          }
      manager.createNotificationChannel(channel)
    }
  }

  private fun buildNotification(sessionBlocked: Long): Notification {
    val stopIntent = Intent(this, LocalAdBlockVpnService::class.java).apply { action = ACTION_STOP }
    val stopPendingIntent =
      PendingIntent.getService(
        this,
        1,
        stopIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )

    return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
      .setSmallIcon(android.R.drawable.ic_lock_lock)
      .setContentTitle(getString(R.string.notification_title_active))
      .setContentText(getString(R.string.notification_text_active, sessionBlocked))
      .setContentIntent(mainActivityPendingIntent())
      .setOngoing(true)
      .setSilent(true)
      .setOnlyAlertOnce(true)
      .addAction(0, getString(R.string.notification_action_pause), stopPendingIntent)
      .build()
  }

  private fun mainActivityPendingIntent(): PendingIntent {
    val intent =
      Intent(this, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
      }
    return PendingIntent.getActivity(
      this,
      0,
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
  }
}
