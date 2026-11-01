package app.pointr.net

import android.graphics.BitmapFactory
import androidx.compose.ui.geometry.Offset
import java.io.BufferedInputStream
import java.io.DataInputStream
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import app.pointr.data.KnownServer
import app.pointr.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.security.KeyPair
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.coroutineContext
import kotlin.math.min

sealed interface ConnState {
    data object Idle : ConnState
    data class Connecting(val name: String, val retry: Boolean) : ConnState
    data class NeedPin(val name: String, val wrong: Boolean, val attempt: Int = 0) : ConnState
    data class Connected(val name: String) : ConnState
    data class Failed(val name: String) : ConnState
}

data class Target(val host: String, val port: Int, val name: String)

/** One frame of the PC monitor under the cursor, plus where that monitor sits on the desktop. */
class ScreenFrame(val image: ImageBitmap, val x: Int, val y: Int, val w: Int, val h: Int)

data class WindowItem(val id: Long, val title: String, val app: String, val icon: ImageBitmap?)

/**
 * One live connection to a Windows PC: a TCP JSON-lines channel for commands and state,
 * plus a signed UDP stream for pointer motion. Reconnects on its own until told otherwise.
 */
class PointrClient(private val prefs: Prefs) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<ConnState>(ConnState.Idle)
    val state: StateFlow<ConnState> = _state

    val volume = MutableStateFlow(0.5f)
    val muted = MutableStateFlow(false)
    val blur = MutableStateFlow(false)
    /** Round-trip time of the control channel, ms; null while not connected. */
    val latency = MutableStateFlow<Int?>(null)

    /** Live view of the PC screen while [setScreen] is on; null otherwise. */
    val screen = MutableStateFlow<ScreenFrame?>(null)
    /** Cursor position within [screen]'s monitor, 0..1. */
    val cursor = MutableStateFlow<Offset?>(null)
    /** The PC has screen viewing switched off. */
    val screenDenied = MutableStateFlow(false)
    @Volatile private var screenWanted = false
    private var screenJob: Job? = null
    private var sessionHost = ""
    private var sessionSid = 0
    private var sessionUdpKey = ByteArray(0)
    @Volatile private var pingSent = 0L
    private val _windows = MutableStateFlow<List<WindowItem>>(emptyList())
    val windows: StateFlow<List<WindowItem>> = _windows

    var serverId: String? = null
        private set

    private var job: Job? = null
    private var outgoing: Channel<String>? = null

    // pairing / auth state of the current session
    private var nonce = ""
    private var keyPair: KeyPair? = null
    private var shared: ByteArray? = null
    private var pinAttempts = 0

    // motion accumulators: move xy, scroll xy, laser xy
    private val acc = FloatArray(6)
    private val udpSignal = Channel<String?>(Channel.UNLIMITED) // null = motion pending, else an ordered command
    @Volatile private var ready = false

    fun connect(target: Target) {
        val previous = job
        job = scope.launch {
            previous?.cancelAndJoin()
            runLoop(target)
        }
    }

    fun disconnect() {
        job?.cancel()
        job = null
        ready = false
        _state.value = ConnState.Idle
    }

    fun submitPin(pin: String) {
        val s = shared ?: return
        sendJson(JSONObject().put("t", "pairProof").put("proof", Crypto.b64(Crypto.hmac(s, "pin:$pin:$nonce"))))
    }

    // ───────────── commands ─────────────

    /**
     * Buttons travel over TCP but motion over UDP, so a click could overtake the last moves
     * and land in the wrong spot (or a drag could start late). Clicks therefore queue behind
     * the motion: the UDP sender flushes pending deltas first, then forwards the click.
     */
    fun click(button: String = "l", action: String = "click") {
        val msg = JSONObject().put("t", "click").put("b", button).put("a", action).toString()
        if (ready) udpSignal.trySend(msg) else outgoing?.trySend(msg)
    }

    fun key(name: String, vararg mods: String) =
        sendJson(JSONObject().put("t", "key").put("k", name).apply { if (mods.isNotEmpty()) put("mods", JSONArray(mods)) })

    fun combo(name: String) = sendJson(JSONObject().put("t", "combo").put("k", name))
    fun text(s: String) = sendJson(JSONObject().put("t", "text").put("s", s))
    fun backspace(n: Int) = sendJson(JSONObject().put("t", "backspace").put("n", n))
    fun setVolume(v: Float) { volume.value = v; sendJson(JSONObject().put("t", "vol").put("v", v.toDouble())) }
    fun toggleMute() = sendJson(JSONObject().put("t", "mute"))
    fun toggleBlur() = sendJson(JSONObject().put("t", "blur"))
    fun laser(on: Boolean) = sendJson(JSONObject().put("t", "laser").put("on", on))
    fun requestWindows() = sendJson(JSONObject().put("t", "windows"))
    fun focus(id: Long) = sendJson(JSONObject().put("t", "focus").put("id", id))

    fun setScreen(on: Boolean) {
        screenWanted = on
        screenDenied.value = false
        if (on) sendJson(JSONObject().put("t", "screen"))
        else stopScreen()
    }

    /** Jump the PC cursor to a point of the frame being shown (0..1 in each axis). */
    fun cursorTo(nx: Float, ny: Float) {
        val f = screen.value ?: return
        sendJson(JSONObject().put("t", "cursorTo").put("x", nx.toDouble()).put("y", ny.toDouble())
            .put("mx", f.x).put("my", f.y).put("mw", f.w).put("mh", f.h))
    }

    private fun stopScreen() {
        screenJob?.cancel()
        screenJob = null
        screen.value = null
        cursor.value = null
    }

    private fun startScreen(port: Int) {
        screenJob?.cancel()
        val host = sessionHost
        val sid = sessionSid
        val proof = Crypto.b64(Crypto.hmac(sessionUdpKey, "screen:${sid.toUInt()}"))
        screenJob = scope.launch {
            val sock = Socket()
            launch { try { awaitCancellation() } finally { runCatching { sock.close() } } }
            try {
                sock.connect(InetSocketAddress(host, port), 3000)
                sock.tcpNoDelay = true
                val out = sock.getOutputStream()
                out.write((JSONObject().put("sid", sid.toUInt().toLong()).put("proof", proof).toString() + "\n").toByteArray())
                out.flush()
                val inp = DataInputStream(BufferedInputStream(sock.getInputStream(), 64 * 1024))
                var buf = ByteArray(256 * 1024)
                while (isActive) {
                    when (inp.readUnsignedByte().toChar()) {
                        'C' -> cursor.value = Offset(inp.readFloat(), inp.readFloat())
                        'F' -> {
                            val x = inp.readInt(); val y = inp.readInt(); val w = inp.readInt(); val h = inp.readInt()
                            val len = inp.readInt()
                            if (len !in 1..(8 shl 20)) throw IOException("bad frame")
                            if (buf.size < len) buf = ByteArray(len)
                            inp.readFully(buf, 0, len)
                            BitmapFactory.decodeByteArray(buf, 0, len)?.let { screen.value = ScreenFrame(it.asImageBitmap(), x, y, w, h) }
                            out.write(1); out.flush() // ack: ready for the next frame
                        }
                        else -> throw IOException("bad message")
                    }
                }
            } catch (_: IOException) {
            } finally {
                runCatching { sock.close() }
            }
        }
    }

    fun move(dx: Float, dy: Float) = accumulate(0, dx, dy)
    fun scroll(dx: Float, dy: Float) = accumulate(2, dx, dy)
    fun laserMove(dx: Float, dy: Float) = accumulate(4, dx, dy)

    private fun accumulate(i: Int, dx: Float, dy: Float) {
        if (!ready) return
        synchronized(acc) { acc[i] += dx; acc[i + 1] += dy }
        udpSignal.trySend(null)
    }

    private fun sendJson(o: JSONObject) {
        outgoing?.trySend(o.toString())
    }

    // ───────────── connection ─────────────

    private suspend fun runLoop(target: Target) {
        var attempt = 0
        var everConnected = false
        while (coroutineContext.isActive) {
            _state.value = ConnState.Connecting(target.name, attempt > 0)
            try {
                session(target) { everConnected = true; attempt = 0 }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // dropped, unreachable or garbled; fall through to retry
            }
            ready = false
            outgoing = null
            if (!coroutineContext.isActive) return
            attempt++
            if (!everConnected && attempt >= 3) {
                _state.value = ConnState.Failed(target.name)
                return
            }
            _state.value = ConnState.Connecting(target.name, true)
            delay(min(600L * attempt, 3000L))
        }
    }

    private suspend fun session(target: Target, onReady: () -> Unit) = coroutineScope {
        val sock = Socket()
        val out = Channel<String>(Channel.UNLIMITED)
        outgoing = out
        // readLine() blocks and ignores cancellation, so closing the socket is how we interrupt it.
        launch { try { awaitCancellation() } finally { runCatching { sock.close() } } }
        sock.connect(InetSocketAddress(target.host, target.port), 3000)
        sock.tcpNoDelay = true
        val writer = sock.getOutputStream().bufferedWriter()
        val reader = sock.getInputStream().bufferedReader()

        val writerJob = launch {
            try {
                for (line in out) { writer.write(line); writer.write("\n"); writer.flush() }
            } catch (_: IOException) { runCatching { sock.close() } }
        }
        out.trySend(JSONObject().put("t", "hello").put("dev", prefs.deviceId).put("name", prefs.deviceName).put("v", 1).toString())

        var key: ByteArray? = null
        var name = target.name
        val background = mutableListOf<Job>()
        try {
            while (isActive) {
                val line = reader.readLine() ?: throw IOException("closed")
                val m = JSONObject(line)
                when (m.optString("t")) {
                    "welcome" -> {
                        nonce = m.getString("nonce")
                        serverId = m.getString("id")
                        name = m.getString("name")
                        val known = prefs.servers.value.firstOrNull { it.id == serverId }
                        key = known?.key?.let(Crypto::unb64)
                        if (m.getBoolean("paired") && key != null) auth(out, key!!)
                        else startPairing(out)
                    }
                    "pairPub" -> {
                        shared = Crypto.sharedSecret(keyPair!!, m.getString("pub"))
                        _state.value = ConnState.NeedPin(name, wrong = false)
                    }
                    "pairFail" -> _state.value = ConnState.NeedPin(name, wrong = true, attempt = ++pinAttempts)
                    "paired" -> {
                        key = Crypto.hmac(shared!!, "key:$nonce")
                        prefs.saveServer(KnownServer(serverId!!, name, target.host, target.port, Crypto.b64(key!!)))
                        auth(out, key!!)
                    }
                    "authFail" -> {
                        // the PC forgot us: drop our key so the retry pairs again
                        prefs.saveServer(KnownServer(serverId!!, name, target.host, target.port, null))
                        throw IOException("auth rejected")
                    }
                    "ready" -> {
                        val sid = m.getLong("sid").toInt()
                        val udpKey = Crypto.hmac(key!!, "udp:$nonce")
                        volume.value = m.optDouble("vol", 0.5).toFloat()
                        muted.value = m.optBoolean("mute")
                        blur.value = m.optBoolean("blur")
                        prefs.saveServer(KnownServer(serverId!!, name, target.host, target.port, Crypto.b64(key!!)))
                        prefs.lastServerId = serverId
                        sessionHost = target.host
                        sessionSid = sid
                        sessionUdpKey = udpKey
                        sock.soTimeout = 9000 // pongs arrive every 3 s; silence means the link is dead
                        synchronized(acc) { acc.fill(0f) }
                        while (udpSignal.tryReceive().isSuccess) Unit // drop what queued while offline
                        ready = true
                        onReady()
                        _state.value = ConnState.Connected(name)
                        if (screenWanted) out.trySend(JSONObject().put("t", "screen").toString())
                        background += launch { udpLoop(target.host, m.getInt("udp"), sid, udpKey) }
                        background += launch {
                            while (isActive) {
                                pingSent = System.nanoTime()
                                out.trySend("""{"t":"ping"}""")
                                delay(2000)
                            }
                        }
                    }
                    "pong" -> if (pingSent != 0L) latency.value = ((System.nanoTime() - pingSent) / 1_000_000).toInt()
                    "vol" -> { volume.value = m.getDouble("v").toFloat(); muted.value = m.getBoolean("m") }
                    "blur" -> blur.value = m.getBoolean("on")
                    "screen" -> if (m.optBoolean("denied")) { screenDenied.value = true; stopScreen() }
                                else if (screenWanted) startScreen(m.getInt("port"))
                    "windows" -> _windows.value = parseWindows(m.getJSONArray("list"))
                }
            }
        } finally {
            ready = false
            latency.value = null
            stopScreen()
            background.forEach { it.cancel() }
            writerJob.cancel()
            out.close()
            runCatching { sock.close() }
        }
    }

    private fun auth(out: Channel<String>, key: ByteArray) {
        out.trySend(JSONObject().put("t", "auth").put("proof", Crypto.b64(Crypto.hmac(key, "auth:$nonce"))).toString())
    }

    private fun startPairing(out: Channel<String>) {
        val kp = Crypto.newKeyPair()
        keyPair = kp
        out.trySend(JSONObject().put("t", "pair").put("pub", Crypto.publicKeyB64(kp)).toString())
    }

    private suspend fun udpLoop(host: String, port: Int, sid: Int, key: ByteArray) {
        val addr = InetAddress.getByName(host)
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
        val buf = ByteBuffer.allocate(26)
        var seq = 0
        DatagramSocket().use { sock ->
            val local = FloatArray(6)
            for (signal in udpSignal) {
                synchronized(acc) { acc.copyInto(local); acc.fill(0f) }
                for (type in 0..2) {
                    val dx = local[type * 2]
                    val dy = local[type * 2 + 1]
                    if (dx == 0f && dy == 0f) continue
                    buf.clear()
                    buf.put('P'.code.toByte()).put((type + 1).toByte()).putInt(sid).putInt(++seq).putFloat(dx).putFloat(dy)
                    mac.update(buf.array(), 0, 18)
                    buf.put(mac.doFinal(), 0, 8)
                    runCatching { sock.send(DatagramPacket(buf.array(), 26, addr, port)) }
                }
                if (signal != null) outgoing?.trySend(signal)
            }
        }
    }

    private val iconCache = HashMap<Int, ImageBitmap?>()

    private fun parseWindows(arr: JSONArray): List<WindowItem> = (0 until arr.length()).map { i ->
        val w = arr.getJSONObject(i)
        val b64 = w.optString("icon").takeIf { it.isNotEmpty() && it != "null" }
        val icon = b64?.let {
            iconCache.getOrPut(it.hashCode()) {
                runCatching {
                    val bytes = Crypto.unb64(it)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                }.getOrNull()
            }
        }
        WindowItem(w.getLong("id"), w.optString("title"), w.optString("app"), icon)
    }
}
