package com.sheen.adb.core

import android.content.Context
import com.sheen.adb.core.internal.DefaultAdbSessionManager
import com.sheen.adb.core.internal.KadbProtocolClientFactory

object AdbManagerProvider {
    fun create(context: Context): AdbSessionManager = DefaultAdbSessionManager(
        clientFactory = KadbProtocolClientFactory(context.applicationContext),
    )
}
