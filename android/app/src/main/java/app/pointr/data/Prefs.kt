package app.pointr.data

import android.content.Context
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.util.UUID

data class UserSettings(
    val pointerSpeed: Float = 1.4f,
    val acceleration: Float = 0.5f,
    val scrollSpeed: Float = 1f,
    val naturalScroll: Boolean = true,
    val haptics: Boolean = true,
    val laserSensitivity: Float = 1f,
    val accent: Int = 0,
    val volumeKeys: Boolean = true,
)

/** A computer this phone has seen. [key] is set once pairing succeeded. */
data class KnownServer(val id: String, val name: String, val host: String, val port: Int, val key: String?)

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("pointr", Context.MODE_PRIVATE)

    val deviceId: String = sp.getString("deviceId", null) ?: UUID.randomUUID().toString().also {
        sp.edit().putString("deviceId", it).apply()
    }

    val deviceName: String = Settings.Global.getString(context.contentResolver, "device_name")
        ?.takeIf { it.isNotBlank() } ?: Build.MODEL

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<UserSettings> = _settings

    private val _servers = MutableStateFlow(loadServers())
    val servers: StateFlow<List<KnownServer>> = _servers

    var lastServerId: String?
        get() = sp.getString("lastServer", null)
        set(v) = sp.edit().putString("lastServer", v).apply()

    fun update(transform: (UserSettings) -> UserSettings) {
        val s = transform(_settings.value)
        _settings.value = s
        sp.edit()
            .putFloat("speed", s.pointerSpeed)
            .putFloat("accel", s.acceleration)
            .putFloat("scroll", s.scrollSpeed)
            .putBoolean("natural", s.naturalScroll)
            .putBoolean("haptics", s.haptics)
            .putFloat("laser", s.laserSensitivity)
            .putInt("accent", s.accent)
            .putBoolean("volumeKeys", s.volumeKeys)
            .apply()
    }

    private fun loadSettings() = UserSettings(
        pointerSpeed = sp.getFloat("speed", 1.4f),
        acceleration = sp.getFloat("accel", 0.5f),
        scrollSpeed = sp.getFloat("scroll", 1f),
        naturalScroll = sp.getBoolean("natural", true),
        haptics = sp.getBoolean("haptics", true),
        laserSensitivity = sp.getFloat("laser", 1f),
        accent = sp.getInt("accent", 0),
        volumeKeys = sp.getBoolean("volumeKeys", true),
    )

    fun saveServer(server: KnownServer) {
        _servers.value = _servers.value.filter { it.id != server.id } + server
        persistServers()
    }

    fun forget(id: String) {
        _servers.value = _servers.value.filter { it.id != id }
        if (lastServerId == id) lastServerId = null
        persistServers()
    }

    private fun persistServers() {
        val o = JSONObject()
        for (s in _servers.value) o.put(s.id, JSONObject().apply {
            put("name", s.name); put("host", s.host); put("port", s.port); put("key", s.key ?: "")
        })
        sp.edit().putString("servers", o.toString()).apply()
    }

    private fun loadServers(): List<KnownServer> {
        val o = runCatching { JSONObject(sp.getString("servers", "{}")!!) }.getOrDefault(JSONObject())
        return o.keys().asSequence().map { id ->
            val s = o.getJSONObject(id)
            KnownServer(id, s.getString("name"), s.getString("host"), s.getInt("port"),
                s.optString("key").takeIf { it.isNotEmpty() })
        }.toList()
    }
}
