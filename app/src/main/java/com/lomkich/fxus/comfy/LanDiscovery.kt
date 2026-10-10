package com.lomkich.fxus.comfy

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * Поиск ComfyUI в локальной сети телефона: и когда телефон раздаёт хотспот (ПК сидит у него),
 * и когда оба в одном Wi-Fi. Перебираем адреса подсетей своих интерфейсов, на открытом порту
 * проверяем /system_stats, чтобы не принять за ComfyUI что-то постороннее.
 */
object LanDiscovery {
    const val DEFAULT_PORT = 8188

    // VPN, мобильная сеть и прочее, где искать бессмысленно
    private val skipIface = Regex("^(lo|tun|wg|ppp|rmnet|ccmni|v4-|clat|dummy|ip6|sit|tap)")
    private val privateHost = Regex("^(10\\.|192\\.168\\.|172\\.(1[6-9]|2[0-9]|3[01])\\.)")

    private val http = OkHttpClient.Builder()
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .readTimeout(2000, TimeUnit.MILLISECONDS)
        .callTimeout(3000, TimeUnit.MILLISECONDS)
        .build()

    /** Адрес вида http://192.168.x.x:порт, то есть имеет смысл искать заново при смене IP. */
    fun isLanUrl(url: String): Boolean {
        val u = url.toHttpUrlOrNull() ?: return false
        return u.scheme == "http" && privateHost.containsMatchIn(u.host)
    }

    fun portOf(url: String): Int {
        val u = url.toHttpUrlOrNull() ?: return DEFAULT_PORT
        return if (u.scheme == "http" && u.port == 80) DEFAULT_PORT else u.port
    }

    /** Отвечает ли по этому адресу ComfyUI. */
    suspend fun ping(baseUrl: String): Boolean = withContext(Dispatchers.IO) {
        try {
            http.newCall(Request.Builder().url("$baseUrl/system_stats").build()).execute().use { r ->
                r.isSuccessful && r.body?.string().orEmpty().contains("\"devices\"")
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun toLong(b: ByteArray): Long = b.fold(0L) { acc, x -> (acc shl 8) or (x.toLong() and 0xFF) }

    private fun fmt(v: Long): String = "${(v shr 24) and 255}.${(v shr 16) and 255}.${(v shr 8) and 255}.${v and 255}"

    /** Все адреса подсетей, где сидит сам телефон (кроме его собственных адресов). */
    private fun candidates(): List<String> {
        val out = LinkedHashSet<String>()
        val ifaces = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        } catch (e: Exception) {
            emptyList()
        }
        for (ni in ifaces) {
            val up = try { ni.isUp && !ni.isLoopback } catch (e: Exception) { false }
            if (!up || skipIface.containsMatchIn(ni.name)) continue
            for (ia in ni.interfaceAddresses) {
                val addr = ia.address as? Inet4Address ?: continue
                if (!addr.isSiteLocalAddress) continue
                val prefix = ia.networkPrefixLength.toInt().coerceIn(24, 30)
                val own = toLong(addr.address)
                val mask = (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
                val net = own and mask
                val size = 1L shl (32 - prefix)
                for (h in (net + 1) until (net + size - 1)) {
                    if (h != own) out.add(fmt(h))
                }
            }
        }
        return out.toList()
    }

    private suspend fun probe(host: String, port: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            Socket().use { it.connect(InetSocketAddress(host, port), 400) }
        } catch (e: IOException) {
            return@withContext false
        }
        ping("http://$host:$port")
    }

    /** Возвращает http://ip:порт первого найденного ComfyUI или null. */
    suspend fun find(port: Int = DEFAULT_PORT): String? = coroutineScope {
        val hosts = candidates()
        if (hosts.isEmpty()) return@coroutineScope null
        val found = CompletableDeferred<String?>()
        val sem = Semaphore(48)
        val jobs = hosts.map { h ->
            launch {
                sem.withPermit {
                    if (!found.isCompleted && probe(h, port)) found.complete("http://$h:$port")
                }
            }
        }
        launch {
            jobs.joinAll()
            found.complete(null)
        }
        val result = found.await()
        coroutineContext.cancelChildren()
        result
    }
}
