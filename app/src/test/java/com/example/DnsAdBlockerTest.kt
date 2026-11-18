package com.example

import com.example.blocker.AdBlocklistEngine
import com.example.vpn.DnsPacketProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsAdBlockerTest {

  @Test
  fun blocklistMatchesExactAndSubdomainsWithoutFalseTldMatch() {
    val engine = AdBlocklistEngine.fromDomains(listOf("doubleclick.net", "ads.example.org"))

    assertTrue(engine.isBlocked("doubleclick.net"))
    assertTrue(engine.isBlocked("ad.doubleclick.net"))
    assertTrue(engine.isBlocked("STATS.G.DOUBLECLICK.NET."))
    assertTrue(engine.isBlocked("banner.ads.example.org"))

    assertFalse(engine.isBlocked("example.org"))
    assertFalse(engine.isBlocked("notdoubleclick.net"))
    assertFalse(engine.isBlocked("net"))
    assertFalse(engine.isBlocked(""))
  }

  @Test
  fun dnsPacketProcessorParsesQueryAndSynthesizesNxDomainWithValidChecksums() {
    val queryPacket = buildSampleIpv4UdpDnsQuery("pagead2.googlesyndication.com")
    val parsed = DnsPacketProcessor.parseDnsQueryPacket(queryPacket, queryPacket.size)

    assertNotNull(parsed)
    parsed!!
    assertEquals("pagead2.googlesyndication.com", parsed.domain)
    assertEquals(1, parsed.qType)
    assertEquals(1, parsed.qClass)
    assertEquals(53, parsed.dstPort)
    assertEquals(54321, parsed.srcPort)

    val reply = DnsPacketProcessor.buildBlockedDnsResponse(parsed)
    // Verify swapped IPs (10.8.0.2 -> 10.8.0.1)
    assertEquals(10, reply[12].toInt() and 0xFF)
    assertEquals(8, reply[13].toInt() and 0xFF)
    assertEquals(0, reply[14].toInt() and 0xFF)
    assertEquals(2, reply[15].toInt() and 0xFF)
    assertEquals(10, reply[16].toInt() and 0xFF)
    assertEquals(8, reply[17].toInt() and 0xFF)
    assertEquals(0, reply[18].toInt() and 0xFF)
    assertEquals(1, reply[19].toInt() and 0xFF)

    // Verify swapped UDP ports (53 -> 54321)
    val replySrcPort = ((reply[20].toInt() and 0xFF) shl 8) or (reply[21].toInt() and 0xFF)
    val replyDstPort = ((reply[22].toInt() and 0xFF) shl 8) or (reply[23].toInt() and 0xFF)
    assertEquals(53, replySrcPort)
    assertEquals(54321, replyDstPort)

    // Verify DNS flags: QR=1, RCODE=3 (NXDOMAIN)
    val dnsFlags = ((reply[30].toInt() and 0xFF) shl 8) or (reply[31].toInt() and 0xFF)
    assertEquals(1, (dnsFlags ushr 15) and 1)
    assertEquals(3, dnsFlags and 0x000F)

    // Verify IPv4 header checksum validates to 0 (RFC 1071 verification)
    assertEquals(0, verifyOnesComplementSum(reply, 0, 20))
  }

  @Test
  fun dnsPacketProcessorIgnoresNonUdpOrNonDnsPackets() {
    val tcpPacket = buildSampleIpv4UdpDnsQuery("example.com")
    tcpPacket[9] = 6 // TCP protocol
    assertNull(DnsPacketProcessor.parseDnsQueryPacket(tcpPacket, tcpPacket.size))

    val nonDnsPort = buildSampleIpv4UdpDnsQuery("example.com")
    nonDnsPort[22] = 0x01
    nonDnsPort[23] = 0xBB.toByte() // Port 443
    assertNull(DnsPacketProcessor.parseDnsQueryPacket(nonDnsPort, nonDnsPort.size))
  }

  private fun buildSampleIpv4UdpDnsQuery(domain: String): ByteArray {
    val dnsPayload = mutableListOf<Byte>()
    // Transaction ID: 0x1234
    dnsPayload.add(0x12)
    dnsPayload.add(0x34)
    // Flags: Standard query with RD=1 (0x0100)
    dnsPayload.add(0x01)
    dnsPayload.add(0x00)
    // QDCOUNT = 1, ANCOUNT = 0, NSCOUNT = 0, ARCOUNT = 0
    dnsPayload.addAll(listOf(0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
    // QNAME
    for (label in domain.split('.')) {
      dnsPayload.add(label.length.toByte())
      for (ch in label) {
        dnsPayload.add(ch.code.toByte())
      }
    }
    dnsPayload.add(0x00)
    // QTYPE = A (1), QCLASS = IN (1)
    dnsPayload.addAll(listOf(0x00, 0x01, 0x00, 0x01))

    val udpLen = 8 + dnsPayload.size
    val totalLen = 20 + udpLen
    val packet = ByteArray(totalLen)

    packet[0] = 0x45
    packet[2] = (totalLen ushr 8).toByte()
    packet[3] = (totalLen and 0xFF).toByte()
    packet[8] = 64
    packet[9] = 17 // UDP
    // Src IP: 10.8.0.1
    packet[12] = 10
    packet[13] = 8
    packet[14] = 0
    packet[15] = 1
    // Dst IP: 10.8.0.2
    packet[16] = 10
    packet[17] = 8
    packet[18] = 0
    packet[19] = 2

    // Src port 54321, Dst port 53
    packet[20] = (54321 ushr 8).toByte()
    packet[21] = (54321 and 0xFF).toByte()
    packet[22] = 0
    packet[23] = 53
    packet[24] = (udpLen ushr 8).toByte()
    packet[25] = (udpLen and 0xFF).toByte()

    dnsPayload.forEachIndexed { idx, b -> packet[28 + idx] = b }
    return packet
  }

  private fun verifyOnesComplementSum(data: ByteArray, offset: Int, length: Int): Int {
    var sum = 0L
    var i = 0
    while (i < length) {
      val word = ((data[offset + i].toInt() and 0xFF) shl 8) or (data[offset + i + 1].toInt() and 0xFF)
      sum += word
      i += 2
    }
    while ((sum ushr 16) != 0L) {
      sum = (sum and 0xFFFF) + (sum ushr 16)
    }
    return (sum.inv() and 0xFFFF).toInt()
  }
}
