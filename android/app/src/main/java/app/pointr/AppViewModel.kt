package app.pointr

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.pointr.data.KnownServer
import app.pointr.data.Prefs
import app.pointr.net.ConnState
import app.pointr.net.Discovery
import app.pointr.net.FoundServer
import app.pointr.net.PointrClient
import app.pointr.net.Target
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val prefs = Prefs(app)
    val client = PointrClient(prefs)
    val found: StateFlow<List<FoundServer>> =
        Discovery(app).scan().stateIn(viewModelScope, SharingStarted.WhileSubscribed(1500), emptyList())

    /** True once this run reached a computer, so a drop shows "Reconnecting" instead of the picker. */
    var hadSession = false
        private set

    private var target: Target? = null
    private var paused = false

    init {
        viewModelScope.launch {
            client.state.collect { if (it is ConnState.Connected) hadSession = true }
        }
        // A PC that answered discovery at a new address (DHCP) gets picked up again automatically.
        viewModelScope.launch {
            found.collect { list ->
                val last = prefs.servers.value.firstOrNull { it.id == prefs.lastServerId && it.key != null } ?: return@collect
                val f = list.firstOrNull { it.id == last.id } ?: return@collect
                val st = client.state.value
                if ((st is ConnState.Idle || st is ConnState.Failed) && !paused) connectTo(f.host, f.port, f.name)
            }
        }
        prefs.servers.value.firstOrNull { it.id == prefs.lastServerId && it.key != null }?.let(::connectKnown)
    }

    fun connectTo(host: String, port: Int, name: String) {
        hadSession = false
        target = Target(host, port, name).also(client::connect)
    }

    fun connectKnown(s: KnownServer) = connectTo(s.host, s.port, s.name)

    fun disconnect() {
        target = null
        hadSession = false
        prefs.lastServerId = null
        client.disconnect()
    }

    fun forget(id: String) {
        if (client.serverId == id) disconnect()
        prefs.forget(id)
    }

    /** Backgrounded: drop the link (no stray laser, no battery drain), resume on return. */
    fun pause() {
        paused = true
        if (client.state.value !is ConnState.NeedPin) client.disconnect() else return
    }

    fun resume() {
        paused = false
        val t = target ?: return
        if (client.state.value is ConnState.Idle) {
            client.connect(t)
        }
    }

    override fun onCleared() = client.disconnect()
}

object BuildConfigCompat {
    fun versionName(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "1.0"
}
