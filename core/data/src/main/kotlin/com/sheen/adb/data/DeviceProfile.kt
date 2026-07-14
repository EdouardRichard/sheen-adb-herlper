package com.sheen.adb.data

data class DeviceProfile(
    val id: String,
    val displayName: String,
    val host: String,
    val debugPort: Int,
    val firstConnectedAtEpochMillis: Long,
    val lastConnectedAtEpochMillis: Long,
    val isLocal: Boolean,
    val identityReference: String,
) {
    init {
        require(id.isNotBlank())
        require(displayName.isNotBlank())
        require(host.isNotBlank())
        require(debugPort in 1..65535)
        require(firstConnectedAtEpochMillis >= 0)
        require(lastConnectedAtEpochMillis >= firstConnectedAtEpochMillis)
        require(identityReference.isNotBlank())
    }
}
