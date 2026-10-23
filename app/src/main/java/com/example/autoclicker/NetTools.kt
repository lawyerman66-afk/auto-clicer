package com.example.autoclicker

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.abs

object NetTools {

    class NtpResult(val offsetMs: Long, val delayMs: Long, val host: String)
    class PingResult(
        val minMs: Double, val avgMs: Double, val maxMs: Double,
        val jitterMs: Double, val lost: Int, val total: Int
    )

    private val NTP_HOSTS = listOf(
        "time.google.com", "pool.ntp.org", "time.cloudflare.com",
        "time.windows.com", "time.android.com"
    )

    private fun readTs(b: ByteArray, off: Int): Long {
        var sec = 0L; var frac = 0L
        for (i in 0..3) sec = (sec shl 8) or (b[off + i].toLong() and 0xFF)
        for (i in 4..7) frac = (frac shl 8) or (b[off + i].toLong() and 0xFF)
        return (sec - 2208988800L) * 1000 + (frac * 1000 / 0x100000000L)
    }

    /** یک نمونه SNTP. خروجی: (اختلاف ساعت، تأخیر رفت‌وبرگشت) به میلی‌ثانیه */
    private fun sntp(host: String): Pair<Long, Long>? {
        val socket = DatagramSocket()
        return try {
            socket.soTimeout = 2000
            val addr = InetAddress.getByName(host)
            val buf = ByteArray(48); buf[0] = 0x1B
            val t0 = System.currentTimeMillis()
            val n0 = System.nanoTime()
            socket.send(DatagramPacket(buf, buf.size, addr, 123))
            val resp = DatagramPacket(ByteArray(48), 48)
            socket.receive(resp)
            val t3 = t0 + (System.nanoTime() - n0) / 1_000_000
            val t1 = readTs(resp.data, 32)
            val t2 = readTs(resp.data, 40)
            Pair(((t1 - t0) + (t2 - t3)) / 2, (t3 - t0) - (t2 - t1))
        } catch (e: Exception) {
            null
        } finally {
            socket.close()
        }
    }

    /** اختلاف ساعت گوشی با ساعت واقعی (مثبت یعنی ساعت گوشی عقب است). باید در ترد پس‌زمینه صدا زده شود. */
    fun ntpOffset(): NtpResult? {
        var best: NtpResult? = null
        var okHosts = 0
        for (h in NTP_HOSTS) {
            var got = false
            repeat(4) {
                val r = sntp(h)
                if (r != null) {
                    got = true
                    if (best == null || r.second < best!!.delayMs) best = NtpResult(r.first, r.second, h)
                }
            }
            if (got) okHosts++
            if (okHosts >= 2) break
        }
        return best
    }

    /** پینگ به‌صورت زمان اتصال TCP به پورت 443. باید در ترد پس‌زمینه صدا زده شود. */
    fun tcpPing(input: String, count: Int = 6): PingResult? {
        val host = input.trim().removePrefix("https://").removePrefix("http://")
            .substringBefore('/').substringBefore(':')
        if (host.isEmpty()) return null
        val addr = try { InetAddress.getByName(host) } catch (e: Exception) { return null }
        val samples = ArrayList<Double>()
        var lost = 0
        repeat(count) {
            try {
                Socket().use { s ->
                    val t0 = System.nanoTime()
                    s.connect(InetSocketAddress(addr, 443), 2000)
                    samples.add((System.nanoTime() - t0) / 1e6)
                }
            } catch (e: Exception) {
                lost++
            }
            Thread.sleep(150)
        }
        if (samples.isEmpty()) return PingResult(0.0, 0.0, 0.0, 0.0, lost, count)
        var jit = 0.0
        for (i in 1 until samples.size) jit += abs(samples[i] - samples[i - 1])
        if (samples.size > 1) jit /= (samples.size - 1)
        return PingResult(samples.min(), samples.average(), samples.max(), jit, lost, count)
    }
}
