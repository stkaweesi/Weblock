package com.sarr.websiteblocker

import java.nio.ByteBuffer

object DnsUtils {

    /** Parses the question name out of a raw DNS message (query or response). */
    fun extractQueryName(dns: ByteArray): String {
        val sb = StringBuilder()
        var pos = 12 // skip the 12-byte DNS header
        while (pos < dns.size) {
            val len = dns[pos].toInt() and 0xFF
            if (len == 0) break
            pos += 1
            if (pos + len > dns.size) break
            sb.append(String(dns, pos, len, Charsets.US_ASCII))
            sb.append('.')
            pos += len
        }
        return sb.toString().trimEnd('.')
    }

    /**
     * Builds a DNS response answering [query] with an all-zero address (0.0.0.0 for A,
     * :: for AAAA) so a blocked lookup fails closed instead of hanging.
     */
    fun buildBlockedResponse(query: ByteArray): ByteArray {
        val id = query.copyOfRange(0, 2)

        var pos = 12
        while (pos < query.size) {
            val len = query[pos].toInt() and 0xFF
            if (len == 0) { pos += 1; break }
            pos += 1 + len
        }
        val qtype = if (pos + 1 < query.size)
            ((query[pos].toInt() and 0xFF) shl 8) or (query[pos + 1].toInt() and 0xFF)
        else 1
        pos += 4 // QTYPE + QCLASS
        val questionSection = query.copyOfRange(12, minOf(pos, query.size))

        val header = ByteBuffer.allocate(12)
        header.put(id)
        header.putShort(0x8180.toShort()) // standard response, recursion available, no error
        header.putShort(1) // QDCOUNT
        header.putShort(1) // ANCOUNT
        header.putShort(0) // NSCOUNT
        header.putShort(0) // ARCOUNT

        val isAAAA = qtype == 28
        val rdata = if (isAAAA) ByteArray(16) else ByteArray(4)

        val answer = ByteBuffer.allocate(10 + rdata.size)
        answer.putShort(0xC00C.toShort()) // pointer to name at offset 12
        answer.putShort(qtype.toShort())
        answer.putShort(1) // CLASS IN
        answer.putInt(60) // TTL
        answer.putShort(rdata.size.toShort())
        answer.put(rdata)

        return header.array() + questionSection + answer.array()
    }

    fun buildIpv4UdpPacket(
        srcIp: ByteArray, dstIp: ByteArray,
        srcPort: Int, dstPort: Int, payload: ByteArray
    ): ByteArray {
        val udpLength = 8 + payload.size
        val totalLength = 20 + udpLength

        val packet = ByteBuffer.allocate(totalLength)
        // IPv4 header
        packet.put((0x45).toByte()) // version 4, IHL 5
        packet.put(0) // DSCP/ECN
        packet.putShort(totalLength.toShort())
        packet.putShort(0) // identification
        packet.putShort(0x4000.toShort()) // flags: don't fragment
        packet.put(64) // TTL
        packet.put(17) // protocol: UDP
        packet.putShort(0) // header checksum placeholder
        packet.put(srcIp)
        packet.put(dstIp)

        // UDP header (checksum 0 is valid/optional for IPv4)
        packet.putShort(srcPort.toShort())
        packet.putShort(dstPort.toShort())
        packet.putShort(udpLength.toShort())
        packet.putShort(0)
        packet.put(payload)

        val bytes = packet.array()
        val ipChecksum = checksum(bytes, 0, 20)
        bytes[10] = (ipChecksum shr 8).toByte()
        bytes[11] = (ipChecksum and 0xFF).toByte()
        return bytes
    }

    private fun checksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0
        var i = offset
        while (i < offset + length - 1) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (length % 2 == 1) {
            sum += (data[offset + length - 1].toInt() and 0xFF) shl 8
        }
        while (sum shr 16 != 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return sum.inv() and 0xFFFF
    }
}
