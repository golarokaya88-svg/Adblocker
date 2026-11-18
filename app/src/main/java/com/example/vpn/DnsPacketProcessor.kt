package com.example.vpn

import java.nio.ByteBuffer
import kotlin.math.min

/**
 * Stateless IPv4 / UDP / DNS packet parser and response builder for local TUN DNS filtering.
 */
object DnsPacketProcessor {

  private const val IP_VERSION_4 = 4
  private const val PROTOCOL_UDP = 17
  private const val DNS_PORT = 53
  private const val MIN_IPV4_HEADER = 20
  private const val UDP_HEADER_SIZE = 8
  private const val DNS_HEADER_SIZE = 12
  private const val MAX_QNAME_JUMPS = 16

  data class ParsedDnsQuery(
    val domain: String,
    val qType: Int,
    val qClass: Int,
    val ipHeaderLength: Int,
    val totalLength: Int,
    val srcIp: ByteArray,
    val dstIp: ByteArray,
    val srcPort: Int,
    val dstPort: Int,
    val dnsPayload: ByteArray,
    val questionEndOffsetInDns: Int,
  ) {
    override fun equals(other: Any?): Boolean {
      if (this === other) return true
      if (other !is ParsedDnsQuery) return false
      return domain == other.domain &&
        qType == other.qType &&
        qClass == other.qClass &&
        srcPort == other.srcPort &&
        dstPort == other.dstPort &&
        srcIp.contentEquals(other.srcIp) &&
        dstIp.contentEquals(other.dstIp) &&
        dnsPayload.contentEquals(other.dnsPayload)
    }

    override fun hashCode(): Int {
      var result = domain.hashCode()
      result = 31 * result + qType
      result = 31 * result + srcPort
      result = 31 * result + srcIp.contentHashCode()
      return result
    }
  }

  /**
   * Parses an IPv4 UDP packet destined for port 53. Returns null for non-IPv4, non-UDP, non-DNS,
   * DNS response packets, or malformed packets.
   */
  fun parseDnsQueryPacket(packet: ByteArray, length: Int): ParsedDnsQuery? {
    if (length < MIN_IPV4_HEADER + UDP_HEADER_SIZE + DNS_HEADER_SIZE) return null

    val version = (packet[0].toInt() ushr 4) and 0x0F
    if (version != IP_VERSION_4) return null

    val ihl = (packet[0].toInt() and 0x0F) * 4
    if (ihl < MIN_IPV4_HEADER || length < ihl + UDP_HEADER_SIZE + DNS_HEADER_SIZE) return null

    val ipTotalLength = readUInt16(packet, 2)
    val effectiveLength = min(length, ipTotalLength)
    if (effectiveLength < ihl + UDP_HEADER_SIZE + DNS_HEADER_SIZE) return null

    val protocol = packet[9].toInt() and 0xFF
    if (protocol != PROTOCOL_UDP) return null

    val udpOffset = ihl
    val srcPort = readUInt16(packet, udpOffset)
    val dstPort = readUInt16(packet, udpOffset + 2)
    if (dstPort != DNS_PORT) return null

    val udpLength = readUInt16(packet, udpOffset + 4)
    val dnsOffset = udpOffset + UDP_HEADER_SIZE
    val dnsLength = min(effectiveLength - dnsOffset, udpLength - UDP_HEADER_SIZE)
    if (dnsLength < DNS_HEADER_SIZE) return null

    val dnsPayload = packet.copyOfRange(dnsOffset, dnsOffset + dnsLength)
    val flags = readUInt16(dnsPayload, 2)
    val isResponse = (flags and 0x8000) != 0
    if (isResponse) return null

    val qdCount = readUInt16(dnsPayload, 4)
    if (qdCount < 1) return null

    val (domain, qNameEnd) = parseQName(dnsPayload, DNS_HEADER_SIZE) ?: return null
    if (qNameEnd + 4 > dnsPayload.size) return null

    val qType = readUInt16(dnsPayload, qNameEnd)
    val qClass = readUInt16(dnsPayload, qNameEnd + 2)

    return ParsedDnsQuery(
      domain = domain,
      qType = qType,
      qClass = qClass,
      ipHeaderLength = ihl,
      totalLength = effectiveLength,
      srcIp = packet.copyOfRange(12, 16),
      dstIp = packet.copyOfRange(16, 20),
      srcPort = srcPort,
      dstPort = dstPort,
      dnsPayload = dnsPayload,
      questionEndOffsetInDns = qNameEnd + 4,
    )
  }

  /**
   * Builds a valid IPv4/UDP DNS NXDOMAIN sinkhole response packet for a blocked query.
   */
  fun buildBlockedDnsResponse(query: ParsedDnsQuery): ByteArray {
    // Keep header + first question section (strip any EDNS0 OPT additional bytes cleanly)
    val dnsLen = min(query.dnsPayload.size, query.questionEndOffsetInDns)
    val dnsReply = query.dnsPayload.copyOf(dnsLen)

    // Preserve RD bit (0x0100), set QR=1 (0x8000), RA=1 (0x0080), RCODE=3 (NXDOMAIN)
    val originalFlags = readUInt16(dnsReply, 2)
    val rdBit = originalFlags and 0x0100
    val replyFlags = 0x8083 or rdBit
    writeUInt16(dnsReply, 2, replyFlags)
    writeUInt16(dnsReply, 4, 1) // QDCOUNT = 1
    writeUInt16(dnsReply, 6, 0) // ANCOUNT = 0
    writeUInt16(dnsReply, 8, 0) // NSCOUNT = 0
    writeUInt16(dnsReply, 10, 0) // ARCOUNT = 0

    return wrapDnsInIpv4UdpPacket(
      srcIp = query.dstIp,
      dstIp = query.srcIp,
      srcPort = query.dstPort,
      dstPort = query.srcPort,
      dnsResponse = dnsReply,
      identification = readUInt16(query.dnsPayload, 0),
    )
  }

  /**
   * Wraps an upstream DNS response payload into a valid IPv4/UDP packet addressed back to the app.
   */
  fun buildForwardedDnsResponse(query: ParsedDnsQuery, upstreamDnsResponse: ByteArray): ByteArray {
    return wrapDnsInIpv4UdpPacket(
      srcIp = query.dstIp,
      dstIp = query.srcIp,
      srcPort = query.dstPort,
      dstPort = query.srcPort,
      dnsResponse = upstreamDnsResponse,
      identification = readUInt16(query.dnsPayload, 0),
    )
  }

  private fun wrapDnsInIpv4UdpPacket(
    srcIp: ByteArray,
    dstIp: ByteArray,
    srcPort: Int,
    dstPort: Int,
    dnsResponse: ByteArray,
    identification: Int,
  ): ByteArray {
    val udpLength = UDP_HEADER_SIZE + dnsResponse.size
    val totalLength = MIN_IPV4_HEADER + udpLength
    val packet = ByteArray(totalLength)

    // IPv4 Header (20 bytes)
    packet[0] = 0x45.toByte() // Version 4, IHL 5 (20 bytes)
    packet[1] = 0x00
    writeUInt16(packet, 2, totalLength)
    writeUInt16(packet, 4, identification)
    writeUInt16(packet, 6, 0x4000) // Don't Fragment
    packet[8] = 64.toByte() // TTL
    packet[9] = PROTOCOL_UDP.toByte()
    writeUInt16(packet, 10, 0) // Checksum placeholder
    System.arraycopy(srcIp, 0, packet, 12, 4)
    System.arraycopy(dstIp, 0, packet, 16, 4)

    val ipChecksum = calculateIpv4Checksum(packet, MIN_IPV4_HEADER)
    writeUInt16(packet, 10, ipChecksum)

    // UDP Header (8 bytes)
    val udpOffset = MIN_IPV4_HEADER
    writeUInt16(packet, udpOffset, srcPort)
    writeUInt16(packet, udpOffset + 2, dstPort)
    writeUInt16(packet, udpOffset + 4, udpLength)
    writeUInt16(packet, udpOffset + 6, 0) // UDP checksum placeholder

    // DNS Payload
    System.arraycopy(dnsResponse, 0, packet, udpOffset + UDP_HEADER_SIZE, dnsResponse.size)

    val udpChecksum =
      calculateUdpChecksum(
        srcIp = srcIp,
        dstIp = dstIp,
        udpSegment = packet,
        udpOffset = udpOffset,
        udpLength = udpLength,
      )
    writeUInt16(packet, udpOffset + 6, udpChecksum)

    return packet
  }

  internal fun parseQName(dnsPayload: ByteArray, startOffset: Int): Pair<String, Int>? {
    val labels = StringBuilder()
    var offset = startOffset
    var endOffset = -1
    var jumps = 0

    while (offset < dnsPayload.size) {
      val len = dnsPayload[offset].toInt() and 0xFF
      if (len == 0) {
        if (endOffset == -1) {
          endOffset = offset + 1
        }
        break
      }

      if ((len and 0xC0) == 0xC0) {
        if (offset + 1 >= dnsPayload.size) return null
        if (endOffset == -1) {
          endOffset = offset + 2
        }
        val pointer = ((len and 0x3F) shl 8) or (dnsPayload[offset + 1].toInt() and 0xFF)
        if (pointer >= dnsPayload.size || ++jumps > MAX_QNAME_JUMPS) return null
        offset = pointer
        continue
      }

      if (len > 63 || offset + 1 + len > dnsPayload.size) return null
      if (labels.isNotEmpty()) labels.append('.')
      for (i in 1..len) {
        labels.append((dnsPayload[offset + i].toInt() and 0xFF).toChar())
      }
      offset += 1 + len
    }

    if (endOffset == -1 || labels.isEmpty()) return null
    return labels.toString().lowercase() to endOffset
  }

  internal fun calculateIpv4Checksum(header: ByteArray, headerLength: Int): Int {
    var sum = 0L
    var i = 0
    while (i < headerLength) {
      if (i != 10) {
        sum += readUInt16(header, i).toLong()
      }
      i += 2
    }
    while ((sum ushr 16) != 0L) {
      sum = (sum and 0xFFFF) + (sum ushr 16)
    }
    return (sum.inv() and 0xFFFF).toInt()
  }

  internal fun calculateUdpChecksum(
    srcIp: ByteArray,
    dstIp: ByteArray,
    udpSegment: ByteArray,
    udpOffset: Int,
    udpLength: Int,
  ): Int {
    var sum = 0L
    sum += readUInt16(srcIp, 0).toLong()
    sum += readUInt16(srcIp, 2).toLong()
    sum += readUInt16(dstIp, 0).toLong()
    sum += readUInt16(dstIp, 2).toLong()
    sum += PROTOCOL_UDP.toLong()
    sum += udpLength.toLong()

    var i = 0
    while (i < udpLength) {
      if (i == 6) {
        i += 2
        continue
      }
      val word =
        if (i + 1 < udpLength) {
          readUInt16(udpSegment, udpOffset + i)
        } else {
          (udpSegment[udpOffset + i].toInt() and 0xFF) shl 8
        }
      sum += word.toLong()
      i += 2
    }

    while ((sum ushr 16) != 0L) {
      sum = (sum and 0xFFFF) + (sum ushr 16)
    }
    val checksum = (sum.inv() and 0xFFFF).toInt()
    return if (checksum == 0) 0xFFFF else checksum
  }

  private fun readUInt16(data: ByteArray, offset: Int): Int {
    return ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
  }

  private fun writeUInt16(data: ByteArray, offset: Int, value: Int) {
    val buffer = ByteBuffer.wrap(data, offset, 2)
    buffer.putShort((value and 0xFFFF).toShort())
  }
}
