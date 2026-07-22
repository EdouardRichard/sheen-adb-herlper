package com.sheen.adb.core

import android.content.Context
import com.sheen.adb.core.internal.DefaultAdbSessionManager
import com.sheen.adb.core.internal.KadbProtocolClientFactory
import com.sheen.adb.core.internal.discovery.AndroidNsdWirelessDiscoverySourceFactory

object AdbManagerProvider {
    @Volatile
    private var manager: AdbSessionManager? = null

    fun create(context: Context): AdbSessionManager = manager ?: synchronized(this) {
        manager ?: context.applicationContext.let { applicationContext ->
            DefaultAdbSessionManager(
                clientFactory = KadbProtocolClientFactory(applicationContext),
                wirelessDiscoverySourceFactory = AndroidNsdWirelessDiscoverySourceFactory(applicationContext),
            ).also { manager = it }
        }
    }
}
