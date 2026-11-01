package app.pointr.net

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.SocketTimeoutException

data class FoundServer(val id: String, val name: String, val host: String, val port: Int)

/** Broadcasts a probe every couple of seconds and emits the current list of answering PCs. */
class Discovery(private val context: Context) {

    fun scan(): Flow<List<FoundServer>> = flow {
        val seen = LinkedHashMap<String, Pair<FoundServer, Long>>()
        DatagramSocket().use { sock ->
            sock.broadcast = true
            sock.soTimeout = 250
            val probe = "PNTR_DISCOVER".toByteArray()
            val buf = ByteArray(2048)
            while (true) {
                for (target in targets()) runCatching {
                    sock.send(DatagramPacket(probe, probe.size, target, PORT))
                }
                val roundEnd = System.currentTimeMillis() + 1800
                while (System.currentTimeMillis() < roundEnd) {
                    val pkt = DatagramPacket(buf, buf.size)
                    try { sock.receive(pkt) } catch (_: SocketTimeoutException) { continue }
                    val found = runCatching {
                        val o = JSONObject(String(pkt.data, 0, pkt.length))
                        if (o.optString("t") != "pointr") null
                        else FoundServer(o.getString("id"), o.getString("name"),
                            pkt.address.hostAddress ?: return@runCatching null, o.getInt("port"))
                    }.getOrNull() ?: continue
                    seen[found.id] = found to System.currentTimeMillis()
                    emit(seen.values.map { it.first })
                }
                val now = System.currentTimeMillis()
                seen.entries.removeAll { now - it.value.second > 7000 }
                emit(seen.values.map { it.first })
                delay(200)
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun targets(): List<InetAddress> {
        val out = mutableListOf<InetAddress>(InetAddress.getByName("255.255.255.255"))
        // Subnet-directed broadcast is what many routers actually forward.
        val cm = context.getSystemService(ConnectivityManager::class.java)
        cm.getLinkProperties(cm.activeNetwork)?.linkAddresses?.forEach { la ->
            val a = la.address as? Inet4Address ?: return@forEach
            val ip = a.address.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }
            val mask = if (la.prefixLength == 0) 0 else -1 shl (32 - la.prefixLength)
            val bc = ip or mask.inv()
            out += InetAddress.getByAddress(byteArrayOf((bc ushr 24).toByte(), (bc ushr 16).toByte(), (bc ushr 8).toByte(), bc.toByte()))
        }
        if (isEmulator) out += InetAddress.getByName("10.0.2.2") // the host PC, as seen from the emulator
        return out
    }

    companion object {
        const val PORT = 47800
        val isEmulator = Build.FINGERPRINT.contains("generic") || Build.HARDWARE.contains("ranchu")
    }
}
