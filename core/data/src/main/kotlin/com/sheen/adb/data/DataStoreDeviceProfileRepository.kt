package com.sheen.adb.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class DataStoreDeviceProfileRepository private constructor(
    private val store: DataStore<Preferences>,
) : DeviceProfileRepository {
    override val profiles: Flow<List<DeviceProfile>> = store.data.map { preferences ->
        preferences[PROFILES].orEmpty().mapNotNull(ProfileCodec::decode)
            .sortedByDescending(DeviceProfile::lastConnectedAtEpochMillis)
    }

    override suspend fun recordSuccessfulConnection(
        host: String,
        port: Int,
        suggestedName: String,
        isLocal: Boolean,
        identityReference: String,
        nowEpochMillis: Long,
    ): DeviceProfile {
        require(host.isNotBlank() && port in 1..65535 && nowEpochMillis >= 0)
        var result: DeviceProfile? = null
        store.edit { preferences ->
            val current = decode(preferences)
            val merged = mergeSuccessfulConnection(
                profiles = current,
                host = host,
                port = port,
                suggestedName = suggestedName,
                isLocal = isLocal,
                identityReference = identityReference,
                nowEpochMillis = nowEpochMillis,
                newId = { UUID.randomUUID().toString() },
            )
            result = merged.second
            preferences[PROFILES] = merged.first.map(ProfileCodec::encode).toSet()
        }
        return checkNotNull(result)
    }

    override suspend fun rename(profileId: String, displayName: String): Boolean {
        val cleaned = displayName.trim().take(80)
        if (cleaned.isBlank()) return false
        var changed = false
        store.edit { preferences ->
            val current = decode(preferences)
            val updated = current.map {
                if (it.id == profileId) {
                    changed = true
                    it.copy(displayName = cleaned)
                } else it
            }
            preferences[PROFILES] = updated.map(ProfileCodec::encode).toSet()
        }
        return changed
    }

    override suspend fun delete(profileId: String): DeviceProfile? {
        var deleted: DeviceProfile? = null
        store.edit { preferences ->
            val current = decode(preferences)
            deleted = current.firstOrNull { it.id == profileId }
            preferences[PROFILES] = current.filterNot { it.id == profileId }.map(ProfileCodec::encode).toSet()
        }
        return deleted
    }

    override suspend fun clearAll() {
        store.edit { it.clear() }
    }

    private fun decode(preferences: Preferences): List<DeviceProfile> =
        preferences[PROFILES].orEmpty().mapNotNull(ProfileCodec::decode)

    companion object {
        private val PROFILES = stringSetPreferencesKey("device_profiles_v1")

        fun create(context: Context): DataStoreDeviceProfileRepository {
            val applicationContext = context.applicationContext
            return DataStoreDeviceProfileRepository(
                PreferenceDataStoreFactory.create(
                    scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                    produceFile = { applicationContext.preferencesDataStoreFile("sheen_local_data") },
                ),
            )
        }
    }
}

internal object ProfileCodec {
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun encode(profile: DeviceProfile): String = listOf(
        VERSION,
        encodeText(profile.id),
        encodeText(profile.displayName),
        encodeText(profile.host),
        profile.debugPort.toString(),
        profile.firstConnectedAtEpochMillis.toString(),
        profile.lastConnectedAtEpochMillis.toString(),
        if (profile.isLocal) "1" else "0",
        encodeText(profile.identityReference),
    ).joinToString("|")

    fun decode(value: String): DeviceProfile? = runCatching {
        val fields = value.split('|')
        require(fields.size == 9 && fields[0] == VERSION)
        DeviceProfile(
            id = decodeText(fields[1]),
            displayName = decodeText(fields[2]),
            host = decodeText(fields[3]),
            debugPort = fields[4].toInt(),
            firstConnectedAtEpochMillis = fields[5].toLong(),
            lastConnectedAtEpochMillis = fields[6].toLong(),
            isLocal = fields[7] == "1",
            identityReference = decodeText(fields[8]),
        )
    }.getOrNull()

    private fun decodeText(value: String): String =
        String(decoder.decode(value), StandardCharsets.UTF_8)

    private fun encodeText(value: String): String =
        encoder.encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private const val VERSION = "1"
}
