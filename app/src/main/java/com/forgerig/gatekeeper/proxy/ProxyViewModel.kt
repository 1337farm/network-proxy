package com.forgerig.gatekeeper.proxy

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import android.content.ComponentName

class ProxyViewModel : ViewModel() {

    val proxyService = androidx.lifecycle.MutableLiveData<ProxyService>()
    val isRunning = androidx.lifecycle.MutableLiveData(false)
    private var bound = false
    private var attached = false
    private var context: Context? = null

    val lastError = androidx.lifecycle.MutableLiveData<String?>(null)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName, service: IBinder) {
            val binder = service as ProxyService.LocalBinder
            val svc = binder.getService()
            svc.setStateCallback { running, error ->
                isRunning.postValue(running)
                lastError.postValue(error)
            }
            proxyService.postValue(svc)
            // Authoritative sync: binder may already be running (e.g. after rotation).
            isRunning.postValue(svc.isRunning())
            lastError.postValue(svc.getLastError())
            bound = true
        }

        override fun onServiceDisconnected(name: android.content.ComponentName) {
            proxyService.postValue(null)
            isRunning.postValue(false)
            bound = false
        }
    }

    fun attach(context: Context) {
        if (attached) {
            // Re-observe after rotation; binding survives via ViewModel.
            val svc: ProxyService? = proxyService.value
            if (svc != null) {
                isRunning.postValue(svc.isRunning())
            }
            return
        }
        attached = true
        this.context = context.applicationContext
        val intent = Intent(this.context, ProxyService::class.java)
        this.context?.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    fun startProxy(port: Int, metricsEnabled: Boolean, mitmEnabled: Boolean = true) {
        startProxy(port, metricsEnabled, mitmEnabled, onlyIfStopped = false)
    }

    /**
     * App-start ensure-running: the service no-ops when already up
     * (no listener flap for a healthy proxy).
     */
    fun ensureRunning(port: Int, metricsEnabled: Boolean, mitmEnabled: Boolean = true) {
        startProxy(port, metricsEnabled, mitmEnabled, onlyIfStopped = true)
    }

    private fun startProxy(port: Int, metricsEnabled: Boolean, mitmEnabled: Boolean, onlyIfStopped: Boolean) {
        val intent = Intent(context, ProxyService::class.java).apply {
            putExtra("port", port)
            putExtra("metricsEnabled", metricsEnabled)
            putExtra("mitmEnabled", mitmEnabled)
            putExtra(ProxyService.EXTRA_ONLY_IF_STOPPED, onlyIfStopped)
        }
        context?.let {
            ContextCompat.startForegroundService(it, intent)
            // Optimistic flip for responsiveness; the service confirms via
            // the state callback (authoritative) once bound.
            isRunning.postValue(true)
            lastError.postValue(null)
        }
    }

    /**
     * Ask the service to re-post its notification now, so a settings change
     * (e.g. the status-bar tok/s toggle) shows up without waiting for a tick.
     * A no-op when the proxy is not running.
     */
    fun refreshNotification() {
        val ctx = context ?: return
        if (isRunning.value != true) return
        ctx.startService(Intent(ctx, ProxyService::class.java).apply {
            action = ProxyService.ACTION_REFRESH_NOTIFICATION
        })
    }

    fun stopProxy() {
        val ctx = context ?: return
        val intent = Intent(ctx, ProxyService::class.java).apply {
            action = ProxyService.ACTION_STOP
        }
        ContextCompat.startForegroundService(ctx, intent)
        // Service calls stopSelf(); reflect stopped state immediately so the
        // button toggles back without waiting for onDestroy/unbind.
        isRunning.postValue(false)
        proxyService.value?.let { /* keep bound for restart */ }
    }

    fun exportMetrics(context: Context): java.io.File {
        val snapshot = ProxyMetrics.snapshot()
        // cacheDir always resolves under a FileProvider <cache-path> root,
        // unlike external files dirs which vary by device/user profile.
        val dir = java.io.File(context.cacheDir, "metrics")
        dir.mkdirs()
        val file = java.io.File(dir, "proxy-metrics-${System.currentTimeMillis()}.json")
        file.writeText(snapshot.toJson())
        return file
    }

    fun clearMetrics() {
        ProxyMetrics.clear()
        ProxyMetrics.resetTallies()
        // Requests/Transferred live in the bound service counters, not in
        // ProxyMetrics — without this, Clear only ever zeroed Retries.
        proxyService.value?.resetStats()
    }

    override fun onCleared() {
        if (bound && context != null) {
            context?.unbindService(serviceConnection)
            bound = false
        }
        super.onCleared()
    }
}