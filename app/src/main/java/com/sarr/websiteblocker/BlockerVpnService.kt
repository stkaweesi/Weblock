package com.sarr.websiteblocker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.concurrent.thread

/**
 * A local, DNS-filtering VPN. It does NOT route your general internet traffic through
 * itself — only DNS lookups (the "what's the IP for x.com?" step) pass through here.
 * Blocked domains get answered with a dead address; everything else is forwarded to a
 * real DNS server and relayed straight back.
 */
class BlockerVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.sarr.websiteblocker.START"
        const val ACTION_STOP = "com.sarr.websiteblocker.STOP"
        private const val TAG = "BlockerVpnService"
        private const val TUN_ADDRESS = "10.111.222.1"
        private const val UPSTREAM_DNS = "1.1.1.1"
        private const val CHANNEL_ID = "website_blocker_channel"
        private const val NOTIF_ID = 1
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    @Volatile private var running = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn()
            return START_NOT_STICKY
        }
        startForegroundWithNotification()
        startVpn()
        return START_STICKY
    }

    private fun startForegroundWithNotification() {
        val notification: Notification
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Website Blocker", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
            notification = Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Website Blocker active")
                .setContentText("Blocking your listed sites")
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .build()
        } else {
            @Suppress("DEPRECATION")
            notification = Notification.Builder(this)
                .setContentTitle("Website Blocker active")
                .setContentText("Blocking your listed sites")
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .build()
        }
        startForeground(NOTIF_ID, notification)
    }

    private fun startVpn() {
        if (running) return
        val builder = Builder()
            .setSession("WebsiteBlocker")
            .addAddress(TUN_ADDRESS, 24)
            .addDnsServer(TUN_ADDRESS)
            .addRoute(TUN_ADDRESS, 32)

        val iface = builder.establish()
        if (iface == null) {
            Log.e(TAG, "Failed to establish VPN interface")
            return
        }
        vpnInterface = iface
        running = true
        thread(start = true) { runLoop(iface) }
    }

    private fun stopVpn() {
        running = false
        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing VPN interface", e)
        }
        vpnInterface = null
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    private fun runLoop(iface: ParcelFileDescriptor) {
        val input = FileInputStream(iface.fileDescriptor)
        val output = FileOutputStream(iface.fileDescriptor)
        val buffer = ByteArray(32767)

        while (running) {
            val length = try {
                input.read(buffer)
            } catch (e: Exception) {
                if (running) Log.e(TAG, "tun read error", e)
                break
            }
            if (length <= 0) continue
            handlePacket(buffer.copyOf(length), output)
        }
    }

    private fun handlePacket(packet: ByteArray, output: FileOutputStream) {
        if (packet.isEmpty()) return
        val version = (packet[0].toInt() shr 4) and 0xF
        if (version != 4) return // IPv4 only for this simple filter

        val ihl = (packet[0].toInt() and 0xF) * 4
        val protocol = packet[9].toInt() and 0xFF
        if (protocol != 17) return // UDP only (DNS)

        val udpStart = ihl
        if (udpStart + 8 > packet.size) return
        val srcPort = ((packet[udpStart].toInt() and 0xFF) shl 8) or (packet[udpStart + 1].toInt() and 0xFF)
        val dstPort = ((packet[udpStart + 2].toInt() and 0xFF) shl 8) or (packet[udpStart + 3].toInt() and 0xFF)
        if (dstPort != 53) return // not a DNS query

        val udpLength = ((packet[udpStart + 4].toInt() and 0xFF) shl 8) or (packet[udpStart + 5].toInt() and 0xFF)
        val dnsStart = udpStart + 8
        if (dnsStart + 12 > packet.size) return
        val dnsPayload = packet.copyOfRange(dnsStart, minOf(packet.size, udpStart + udpLength))

        val queryName = try {
            DnsUtils.extractQueryName(dnsPayload)
        } catch (e: Exception) {
            null
        } ?: return

        val srcIp = packet.copyOfRange(12, 16)
        val dstIp = packet.copyOfRange(16, 20)
        val blocked = BlocklistStore.isBlocked(queryName, BlocklistStore.getBlockedDomains(this))

        if (blocked) {
            val response = DnsUtils.buildBlockedResponse(dnsPayload)
            val replyPacket = DnsUtils.buildIpv4UdpPacket(
                srcIp = dstIp, dstIp = srcIp, srcPort = 53, dstPort = srcPort, payload = response
            )
            try {
                output.write(replyPacket)
            } catch (e: Exception) {
                Log.e(TAG, "write error", e)
            }
        } else {
            thread(start = true) { forwardQuery(dnsPayload, srcIp, dstIp, srcPort, output) }
        }
    }

    private fun forwardQuery(
        dnsPayload: ByteArray, srcIp: ByteArray, dstIp: ByteArray, srcPort: Int, output: FileOutputStream
    ) {
        try {
            val socket = DatagramSocket()
            protect(socket) // send outside the VPN, or this would loop back into itself
            socket.soTimeout = 4000
            socket.send(DatagramPacket(dnsPayload, dnsPayload.size, InetAddress.getByName(UPSTREAM_DNS), 53))

            val replyBuffer = ByteArray(2048)
            val replyDatagram = DatagramPacket(replyBuffer, replyBuffer.size)
            socket.receive(replyDatagram)
            socket.close()

            val answer = replyDatagram.data.copyOfRange(0, replyDatagram.length)
            val replyPacket = DnsUtils.buildIpv4UdpPacket(
                srcIp = dstIp, dstIp = srcIp, srcPort = 53, dstPort = srcPort, payload = answer
            )
            output.write(replyPacket)
        } catch (e: Exception) {
            Log.e(TAG, "forward error", e)
        }
    }
}
