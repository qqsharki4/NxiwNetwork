package com.nxiwnetwork.client

import android.app.Application
import android.content.Context
import com.wireguard.android.backend.GoBackend

class NxiwApplication : Application() {
    @Volatile
    private var backendInstance: GoBackend? = null

    val backend: GoBackend
        get() = getBackend(this)

    override fun onCreate() {
        super.onCreate()
        NodesManager.init(this)
    }

    fun getBackend(context: Context): GoBackend {
        return backendInstance ?: synchronized(this) {
            backendInstance ?: GoBackend(context.applicationContext).also { backendInstance = it }
        }
    }
}
