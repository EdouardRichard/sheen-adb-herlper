package com.sheen.adb.data

import kotlinx.coroutines.flow.Flow

interface DeviceProfileRepository {
    val profiles: Flow<List<DeviceProfile>>

    suspend fun recordSuccessfulConnection(
        host: String,
        port: Int,
        suggestedName: String,
        isLocal: Boolean,
        identityReference: String,
        nowEpochMillis: Long,
    ): DeviceProfile

    suspend fun rename(profileId: String, displayName: String): Boolean
    suspend fun delete(profileId: String): DeviceProfile?
    suspend fun clearAll()
}
