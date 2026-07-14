package com.sheen.adb.data

internal fun mergeSuccessfulConnection(
    profiles: List<DeviceProfile>,
    host: String,
    port: Int,
    suggestedName: String,
    isLocal: Boolean,
    identityReference: String,
    nowEpochMillis: Long,
    newId: () -> String,
): Pair<List<DeviceProfile>, DeviceProfile> {
    val existing = profiles.firstOrNull { it.host.equals(host, ignoreCase = true) }
    val updated = if (existing == null) {
        DeviceProfile(
            id = newId(),
            displayName = suggestedName.ifBlank { "未命名设备" },
            host = host,
            debugPort = port,
            firstConnectedAtEpochMillis = nowEpochMillis,
            lastConnectedAtEpochMillis = nowEpochMillis,
            isLocal = isLocal,
            identityReference = identityReference,
        )
    } else {
        existing.copy(
            debugPort = port,
            lastConnectedAtEpochMillis = maxOf(nowEpochMillis, existing.firstConnectedAtEpochMillis),
            isLocal = isLocal,
            identityReference = identityReference,
        )
    }
    return (profiles.filterNot { it.id == updated.id } + updated)
        .sortedByDescending(DeviceProfile::lastConnectedAtEpochMillis) to updated
}
