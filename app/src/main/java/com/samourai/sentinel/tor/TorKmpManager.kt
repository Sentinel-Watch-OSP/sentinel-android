package com.samourai.sentinel.tor

import android.app.Application
import android.widget.Toast
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import io.matthewnelson.kmp.tor.resource.exec.tor.ResourceLoaderTorExec
import io.matthewnelson.kmp.tor.runtime.Action
import io.matthewnelson.kmp.tor.runtime.RuntimeEvent
import io.matthewnelson.kmp.tor.runtime.TorRuntime
import io.matthewnelson.kmp.tor.runtime.core.OnEvent
import io.matthewnelson.kmp.tor.runtime.core.OnFailure
import io.matthewnelson.kmp.tor.runtime.core.OnSuccess
import io.matthewnelson.kmp.tor.runtime.core.config.TorOption
import io.matthewnelson.kmp.tor.runtime.core.ctrl.TorCmd
import io.matthewnelson.kmp.tor.runtime.service.TorServiceConfig
import java.net.InetSocketAddress
import java.net.Proxy

/**
 * Tor via kmp-tor 2.x (tor 0.4.9.x). The 1.x line topped out at tor 0.4.8,
 * which the network stopped accepting on 2026-10-28 (missing Desc=3/Microdesc=3).
 */
class TorKmpManager(application: Application) {

    private val TAG = "TorListener"

    private val runtime: TorRuntime by lazy {
        val environment = TorServiceConfig.Builder {
            // Tor runs in a background service; stop it with the app.
            stopServiceOnTaskRemoved = true
        }.newEnvironment(ResourceLoaderTorExec::getOrCreate)

        TorRuntime.Builder(environment) {
            val executor = OnEvent.Executor.Immediate

            observerStatic(RuntimeEvent.STATE, executor) { state -> onState(state.daemon) }
            observerStatic(RuntimeEvent.LISTENERS, executor) { listeners ->
                proxy = listeners.socks.firstOrNull()?.let { sa ->
                    Proxy(Proxy.Type.SOCKS, InetSocketAddress(sa.address.value, sa.port.value))
                }
            }
            observerStatic(RuntimeEvent.ERROR, executor) { t -> listener.addLine("ERROR: $t") }
            observerStatic(RuntimeEvent.LOG.WARN, executor) { line -> listener.addLine(line) }
            observerStatic(RuntimeEvent.LOG.INFO, executor) { line -> listener.addLine(line) }

            config { _ ->
                // Let tor pick a free port; the proxy is taken from LISTENERS.
                TorOption.__SocksPort.configure { auto() }

                // For Android, disabling & reducing connection padding is
                // advisable to minimize mobile data usage.
                TorOption.ConnectionPadding.configure { disable() }
                TorOption.ReducedConnectionPadding.configure(true)
            }
        }
    }

    private val listener = TorListener()

    val events: LiveData<String> get() = listener.eventLines

    val torStateLiveData: MutableLiveData<TorState> = MutableLiveData()
        get() = field
    var torState: TorState = TorState()
        get() = field

    @Volatile
    var proxy: Proxy? = null
        get() = field

    private val appContext = application

    fun isConnected(): Boolean {
        val daemon = runtime.state().daemon
        return daemon.isOn && daemon.isBootstrapped
    }

    fun isStarting(): Boolean {
        val daemon = runtime.state().daemon
        return daemon.isStarting || (daemon.isOn && !daemon.isBootstrapped)
    }

    fun start() {
        runtime.enqueue(Action.StartDaemon, OnFailure.noOp(), OnSuccess.noOp())
    }

    fun stop() {
        runtime.enqueue(Action.StopDaemon, OnFailure.noOp(), OnSuccess.noOp())
    }

    fun newIdentity(appContext: Application) {
        runtime.enqueue(
            TorCmd.Signal.NewNym,
            { t ->
                val msg = "Tor identity change failed"
                listener.addLine("$msg: $t")
                toast(appContext, msg)
            },
            { _ ->
                val msg = "Tor identity changed"
                listener.addLine(msg)
                toast(appContext, msg)
            },
        )
    }

    private fun toast(context: Application, msg: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun onState(daemon: io.matthewnelson.kmp.tor.runtime.TorState.Daemon) {
        val newState = when {
            daemon.isOn && daemon.isBootstrapped -> EnumTorState.ON
            daemon.isOn || daemon.isStarting -> EnumTorState.STARTING
            daemon.isStopping -> EnumTorState.STOPPING
            else -> EnumTorState.OFF
        }
        if (newState == EnumTorState.OFF) proxy = null

        torState.state = newState
        torState.progressIndicator = daemon.bootstrap.toInt()

        val liveTorState = TorState()
        liveTorState.state = newState
        liveTorState.progressIndicator = daemon.bootstrap.toInt()
        torStateLiveData.postValue(liveTorState)
    }

    private inner class TorListener {
        private val _eventLines: MutableLiveData<String> = MutableLiveData("")
        val eventLines: LiveData<String> = _eventLines
        private val events: MutableList<String> = ArrayList(50)
        fun addLine(line: String) {
            synchronized(this) {
                if (events.size > 49) {
                    events.removeAt(0)
                }
                events.add(line)
                _eventLines.postValue(events.joinToString("\n"))
            }
        }
    }
}
