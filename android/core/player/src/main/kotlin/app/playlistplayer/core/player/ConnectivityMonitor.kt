package app.playlistplayer.core.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Whether the device currently has a network with internet capability. */
interface ConnectivityMonitor {
    val isConnected: StateFlow<Boolean>

    companion object {
        /** Always connected; for tests and callers that do not track connectivity. */
        val ALWAYS_CONNECTED: ConnectivityMonitor = object : ConnectivityMonitor {
            override val isConnected: StateFlow<Boolean> = MutableStateFlow(true).asStateFlow()
        }
    }
}

/**
 * [ConnectivityMonitor] over the default network callback. Requires ACCESS_NETWORK_STATE
 * (declared in this module's manifest). Call [close] when done.
 */
class AndroidConnectivityMonitor(context: Context) : ConnectivityMonitor, AutoCloseable {

    private val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val state = MutableStateFlow(currentlyConnected())
    override val isConnected: StateFlow<Boolean> = state.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            state.value = hasInternet(manager.getNetworkCapabilities(network)) || currentlyConnected()
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            state.value = hasInternet(capabilities)
        }

        override fun onLost(network: Network) {
            state.value = currentlyConnected()
        }
    }

    init {
        manager.registerDefaultNetworkCallback(callback)
    }

    private fun currentlyConnected(): Boolean =
        hasInternet(manager.activeNetwork?.let(manager::getNetworkCapabilities))

    private fun hasInternet(capabilities: NetworkCapabilities?): Boolean =
        capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

    override fun close() {
        runCatching { manager.unregisterNetworkCallback(callback) }
    }
}
