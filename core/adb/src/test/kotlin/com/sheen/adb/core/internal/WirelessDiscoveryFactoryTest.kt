package com.sheen.adb.core.internal

import android.content.Context
import android.content.ContextWrapper
import com.sheen.adb.core.AdbManagerProvider
import com.sheen.adb.core.WirelessDiscoveryEvent
import com.sheen.adb.core.WirelessDiscoveryMode
import com.sheen.adb.core.WirelessDiscoverySourceFactory
import com.sheen.adb.core.WirelessDiscoverySourceFailure
import com.sheen.adb.core.WirelessDiscoverySourceObserver
import com.sheen.adb.core.WirelessDiscoverySourceRequest
import com.sheen.adb.core.WirelessDiscoverySourceStartResult
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executor
import org.testng.Assert.assertFalse
import org.testng.Assert.assertNotNull
import org.testng.Assert.assertSame
import org.testng.Assert.assertTrue
import org.testng.annotations.Test
import sun.misc.Unsafe

class WirelessDiscoveryFactoryTest {
    @Test
    fun `provider owns one application scoped manager and a closeable discovery source`() {
        val directory = Files.createTempDirectory("wireless-provider-").toFile()
        val application = allocate<TestApplicationContext>().also { it.directory = directory }
        val activity = allocate<ActivityScopedContext>().also { it.application = application }

        val fromActivity = AdbManagerProvider.create(activity)
        val fromApplication = AdbManagerProvider.create(application)
        try {
            assertSame(fromActivity, fromApplication, "The process must own exactly one ADB manager")

            val factory = discoveryFactory(fromActivity)
            assertNotNull(factory, "The provider must install the Android NSD discovery factory")
            assertTrue(
                contextFields(factory!!).any { it === application },
                "The discovery factory must retain the application Context",
            )
            assertFalse(
                contextFields(factory).any { it === activity },
                "The discovery factory must not retain the Activity-scoped Context",
            )

            val source = factory.create(NoOpObserver)
            source.close()
            source.close()
            assertTrue(
                source.start(
                    WirelessDiscoverySourceRequest(
                        generation = 1,
                        mode = WirelessDiscoveryMode.LAN_FOREGROUND,
                    ),
                ) is
                    WirelessDiscoverySourceStartResult.Rejected,
                "A closed Android discovery source must reject restart",
            )
        } finally {
            if (fromApplication !== fromActivity) fromApplication.close()
            fromActivity.close()
            resetProvider()
            directory.deleteRecursively()
        }
    }

    private fun discoveryFactory(manager: Any): WirelessDiscoverySourceFactory? {
        val field = manager.javaClass.getDeclaredField("wirelessDiscoverySourceFactory")
        field.isAccessible = true
        return field.get(manager) as? WirelessDiscoverySourceFactory
    }

    private fun contextFields(instance: Any): List<Context> = instance.javaClass.declaredFields.mapNotNull { field ->
        field.isAccessible = true
        field.get(instance) as? Context
    }

    private fun resetProvider() {
        runCatching {
            AdbManagerProvider::class.java.getDeclaredField("manager").run {
                isAccessible = true
                set(AdbManagerProvider, null)
            }
        }
    }

    private inline fun <reified T> allocate(): T = unsafe.allocateInstance(T::class.java) as T

    private class TestApplicationContext private constructor() : ContextWrapper(null) {
        lateinit var directory: File

        override fun getApplicationContext(): Context = this

        override fun getNoBackupFilesDir(): File = directory

        override fun getMainExecutor(): Executor = Executor(Runnable::run)
    }

    private class ActivityScopedContext private constructor() : ContextWrapper(null) {
        lateinit var application: Context

        override fun getApplicationContext(): Context = application
    }

    private object NoOpObserver : WirelessDiscoverySourceObserver {
        override fun onEvent(event: WirelessDiscoveryEvent) = Unit

        override fun onFailure(failure: WirelessDiscoverySourceFailure) = Unit
    }

    private companion object {
        val unsafe: Unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").run {
            isAccessible = true
            get(null) as Unsafe
        }
    }
}
