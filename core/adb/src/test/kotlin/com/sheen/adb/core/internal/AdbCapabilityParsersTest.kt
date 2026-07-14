package com.sheen.adb.core.internal

import org.testng.Assert.assertEquals
import org.testng.Assert.assertNull
import org.testng.Assert.assertTrue
import org.testng.annotations.Test

class AdbCapabilityParsersTest {
    @Test
    fun `parses overview fields and leaves unavailable data null`() {
        val overview = AdbCapabilityParsers.overview(
            propertiesText = """
                [ro.product.brand]: [Example]
                [ro.product.model]: [Tablet]
                [ro.build.version.release]: [16]
                [ro.build.version.sdk]: [36]
            """.trimIndent(),
            memoryText = "MemTotal: 1000 kB\nMemAvailable: 250 kB",
            storageText = "Filesystem 1K-blocks Used Available Use% Mounted on\n/data 2000 500 1500 25% /data",
            batteryText = "level: 80\nstatus: 2\ntemperature: 315",
            uptimeText = "1234.50 0.00",
            coresText = "8",
            networkText = "2: wlan0 inet 192.0.2.8/24 scope global wlan0",
        )

        assertEquals(overview.brand, "Example")
        assertEquals(overview.model, "Tablet")
        assertNull(overview.manufacturer)
        assertEquals(overview.memoryTotalBytes, 1_024_000L)
        assertEquals(overview.storageAvailableBytes, 1_536_000L)
        assertEquals(overview.temperatureCelsius, 31.5)
        assertEquals(overview.availableCores, 8)
        assertEquals(overview.networkAddresses, listOf("192.0.2.8"))
    }

    @Test
    fun `parses extended and degraded process formats`() {
        val full = AdbCapabilityParsers.processes(
            "USER PID PPID VSZ RSS S NAME\nu0_a1 123 1 1000 64 S example.process",
        )
        assertEquals(full.processes.single().pid, 123)
        assertEquals(full.processes.single().residentMemoryBytes, 65_536L)
        assertNull(full.degradedReason)

        val degraded = AdbCapabilityParsers.processes("USER PID NAME\nu0_a2 456 other.process")
        assertTrue(degraded.degradedReason?.contains("内存") == true)
        assertNull(degraded.processes.single().state)
    }
}
