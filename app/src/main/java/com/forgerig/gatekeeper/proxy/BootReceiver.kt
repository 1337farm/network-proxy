package com.forgerig.gatekeeper.proxy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.core.content.ContextCompat

/**
 * Keys shared by the UI and the receiver. Deliberately the same
 * SharedPreferences file MainActivity has always used, so an existing
 * install's Stop opt-out is honoured without a migration.
 */
internal object ProxyPrefs {
    const val NAME = "gatekeeper"
    const val SHOULD_RUN = "proxyShouldRun"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun shouldRun(context: Context): Boolean =
        prefs(context).getBoolean(SHOULD_RUN, true)

    fun setShouldRun(context: Context, run: Boolean) {
        prefs(context).edit().putBoolean(SHOULD_RUN, run).apply()
    }
}

/**
 * Brings the proxy back up on its own.
 *
 * This proxy is the device's only route to the model provider, so a dead
 * service means total loss of connectivity, not a degraded feature. Two
 * ordinary events leave it dead with nothing to bring it back: a reboot
 * (nothing starts the service), and an app upgrade (updating a package
 * stops its services). The second one fires on every CI sideload, so the
 * usual workflow of "install the new APK, keep working" silently ended
 * with an offline device until the app was opened by hand.
 *
 * [ProxyService.onStartCommand] already returns START_STICKY, which covers
 * the process being killed, but it does nothing for these two cases.
 *
 * A user-initiated Stop still wins: [ProxyPrefs.SHOULD_RUN] is the same
 * flag the Stop button writes, so an explicit opt-out survives the reboot
 * instead of being quietly undone.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (!isHandledAction(intent.action)) return
        if (!ProxyPrefs.shouldRun(context)) return

        ContextCompat.startForegroundService(
            context,
            // No port to pass: there is only one, in PROXY_PORT.
            Intent(context, ProxyService::class.java).apply {
                // Do not flap the listener if the service is already up:
                // an update can deliver this while it is still running.
                putExtra(ProxyService.EXTRA_ONLY_IF_STOPPED, true)
            }
        )
    }

    companion object {
        /**
         * LOCKED_BOOT_COMPLETED is intentionally absent: it fires before
         * the user unlocks, and reading credentials-encrypted
         * SharedPreferences that early fails, so the opt-out could not be
         * honoured. BOOT_COMPLETED fires after unlock, which is soon enough.
         */
        fun isHandledAction(action: String?): Boolean =
            action == Intent.ACTION_BOOT_COMPLETED ||
                action == Intent.ACTION_MY_PACKAGE_REPLACED
    }
}
