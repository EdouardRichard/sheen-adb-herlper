package com.sheen.adb.core.internal

import com.sheen.adb.core.DeviceOverview
import com.sheen.adb.core.DeviceProcess
import com.sheen.adb.core.DynamicDeviceMetrics
import com.sheen.adb.core.LogcatConfig
import com.sheen.adb.core.ProcessSnapshot

internal object AdbCommands {
    const val PROPERTIES = "getprop"
    const val MEMORY = "cat /proc/meminfo"
    const val STORAGE = "df -k /data"
    const val BATTERY = "dumpsys battery"
    const val UPTIME = "cat /proc/uptime"
    const val CORES = "getconf _NPROCESSORS_ONLN"
    const val NETWORK = "ip -o addr show scope global"
    const val PROCESSES_EXTENDED = "ps -A -o USER,PID,PPID,VSZ,RSS,S,NAME"
    const val PROCESSES_FALLBACK = "ps -A"

    fun logcat(config: LogcatConfig): String = buildString {
        append("logcat -v threadtime")
        config.buffers.sortedBy { it.ordinal }.forEach { append(" -b ").append(it.argument) }
        append(" '*:").append(config.minimumLevel.argument).append("'")
    }
}

internal object AdbCapabilityParsers {
    fun overview(
        propertiesText: String,
        memoryText: String,
        storageText: String,
        batteryText: String,
        uptimeText: String,
        coresText: String,
        networkText: String,
    ): DeviceOverview {
        val properties = parseProperties(propertiesText)
        val memory = dynamic(memoryText, batteryText, uptimeText)
        val storage = parseStorage(storageText)
        return DeviceOverview(
            brand = properties.value("ro.product.brand"),
            manufacturer = properties.value("ro.product.manufacturer"),
            model = properties.value("ro.product.model"),
            deviceCode = properties.value("ro.product.device"),
            androidVersion = properties.value("ro.build.version.release"),
            sdk = properties.value("ro.build.version.sdk"),
            buildDisplay = properties.value("ro.build.display.id"),
            buildFingerprint = properties.value("ro.build.fingerprint"),
            securityPatch = properties.value("ro.build.version.security_patch"),
            cpuAbi = properties.value("ro.product.cpu.abi"),
            availableCores = coresText.trim().toIntOrNull()?.takeIf { it > 0 },
            memoryTotalBytes = memory.memoryTotalBytes,
            memoryAvailableBytes = memory.memoryAvailableBytes,
            storageTotalBytes = storage.first,
            storageAvailableBytes = storage.second,
            batteryPercent = memory.batteryPercent,
            chargingState = memory.chargingState,
            temperatureCelsius = memory.temperatureCelsius,
            uptimeSeconds = memory.uptimeSeconds,
            networkAddresses = parseNetworkAddresses(networkText),
        )
    }

    fun dynamic(memoryText: String, batteryText: String, uptimeText: String): DynamicDeviceMetrics {
        val memory = parseKeyValues(memoryText)
        val battery = parseKeyValues(batteryText)
        val status = when (battery["status"]?.toIntOrNull()) {
            2 -> "充电中"
            3 -> "未充电"
            4 -> "未在充电"
            5 -> "已充满"
            else -> null
        }
        return DynamicDeviceMetrics(
            memoryTotalBytes = memory.kib("MemTotal"),
            memoryAvailableBytes = memory.kib("MemAvailable") ?: memory.kib("MemFree"),
            batteryPercent = battery["level"]?.toIntOrNull()?.takeIf { it in 0..100 },
            chargingState = status,
            temperatureCelsius = battery["temperature"]?.toDoubleOrNull()?.div(10.0),
            uptimeSeconds = uptimeText.trim().substringBefore(' ').toDoubleOrNull()?.toLong(),
        )
    }

    fun processes(text: String): ProcessSnapshot {
        val lines = text.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        if (lines.size < 2) return ProcessSnapshot(emptyList(), "设备未提供可解析的进程列表")
        val headers = lines.first().split(Regex("\\s+")).map(String::uppercase)
        val pidIndex = headers.indexOf("PID")
        val nameIndex = listOf("NAME", "CMD", "COMMAND", "ARGS").firstNotNullOfOrNull { key ->
            headers.indexOf(key).takeIf { it >= 0 }
        } ?: headers.lastIndex
        if (pidIndex < 0) return ProcessSnapshot(emptyList(), "ROM 的 ps 输出缺少 PID 字段")
        val uidIndex = headers.indexOf("UID").takeIf { it >= 0 } ?: headers.indexOf("USER")
        val stateIndex = headers.indexOf("S").takeIf { it >= 0 } ?: headers.indexOf("STAT")
        val rssIndex = headers.indexOf("RSS")
        val processes = lines.drop(1).mapNotNull { line ->
            val fields = line.split(Regex("\\s+"))
            val pid = fields.getOrNull(pidIndex)?.toIntOrNull() ?: return@mapNotNull null
            val name = if (nameIndex < fields.size) fields.drop(nameIndex).joinToString(" ") else return@mapNotNull null
            DeviceProcess(
                name = name,
                pid = pid,
                uid = fields.getOrNull(uidIndex)?.takeIf(String::isNotBlank),
                state = fields.getOrNull(stateIndex)?.takeIf(String::isNotBlank),
                residentMemoryBytes = fields.getOrNull(rssIndex)?.toLongOrNull()?.times(1024),
            )
        }
        val missing = buildList {
            if (uidIndex < 0) add("UID")
            if (stateIndex < 0) add("状态")
            if (rssIndex < 0) add("内存")
        }
        return ProcessSnapshot(
            processes = processes,
            degradedReason = missing.takeIf { it.isNotEmpty() }?.joinToString(
                prefix = "设备未提供：",
                separator = "、",
            ),
        )
    }

    private fun parseProperties(text: String): Map<String, String> = buildMap {
        val pattern = Regex("^\\[([^]]+)]: \\[([^]]*)]$")
        text.lineSequence().forEach { line ->
            pattern.matchEntire(line.trim())?.let { put(it.groupValues[1], it.groupValues[2]) }
        }
    }

    private fun Map<String, String>.value(key: String): String? = get(key)?.trim()?.takeIf(String::isNotEmpty)

    private fun parseKeyValues(text: String): Map<String, String> = buildMap {
        text.lineSequence().forEach { line ->
            val key = line.substringBefore(':', "").trim()
            if (key.isNotEmpty()) put(key, line.substringAfter(':').trim().substringBefore(' '))
        }
    }

    private fun Map<String, String>.kib(key: String): Long? = get(key)?.toLongOrNull()?.times(1024)

    private fun parseStorage(text: String): Pair<Long?, Long?> {
        val lines = text.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        val headerIndex = lines.indexOfFirst { "Available" in it || "Avail" in it }
        val fields = lines.getOrNull(headerIndex + 1)?.split(Regex("\\s+")).orEmpty()
        return fields.getOrNull(1)?.toLongOrNull()?.times(1024) to
            fields.getOrNull(3)?.toLongOrNull()?.times(1024)
    }

    private fun parseNetworkAddresses(text: String): List<String> = text.lineSequence().mapNotNull { line ->
        Regex("\\binet6?\\s+([^\\s/]+)").find(line)?.groupValues?.get(1)
    }.filterNot { it == "127.0.0.1" || it == "::1" }.distinct().toList()
}
