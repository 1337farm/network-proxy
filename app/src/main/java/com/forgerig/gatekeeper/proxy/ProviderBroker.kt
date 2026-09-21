package com.forgerig.gatekeeper.proxy

import android.content.Context

/**
 * Process-wide holder for the [ProviderStore].
 *
 * The store is encrypted at rest; keep one decrypted copy in memory and
 * reload it when the UI saves changes (cheap, small JSON).
 */
object ProviderBroker {
    @Volatile private var cached: ProviderStore? = null

    @Synchronized
    fun store(context: Context): ProviderStore {
        cached?.let { return it }
        return ProviderStore.loadOrBlank(context.applicationContext).also { cached = it }
    }

    @Synchronized
    fun save(context: Context, store: ProviderStore) {
        store.save(context.applicationContext)
        cached = store
    }

    @Synchronized
    fun invalidate() {
        cached = null
    }
}
