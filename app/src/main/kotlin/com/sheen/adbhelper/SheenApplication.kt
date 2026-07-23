package com.sheen.adbhelper

import android.app.Application
import com.sheen.adb.core.AdbManagerProvider
import com.sheen.adb.data.AppTemporaryDataCleaner
import com.sheen.adb.data.CompositeTemporaryDataCleaner
import com.sheen.adb.data.DataStoreDeviceProfileRepository
import com.sheen.adb.data.SafTextExporter
import com.sheen.adb.data.SafDocumentStore
import com.sheen.adb.data.SafTemporaryDataCleaner
import com.sheen.adbhelper.localpairing.AndroidLocalPairingServiceLifecycle
import com.sheen.adbhelper.localpairing.LocalPairingAppBridge

class SheenApplication : Application() {
    val container: AppContainer by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { AppContainer(this) }
}

class AppContainer(application: Application) {
    val adbManager = AdbManagerProvider.create(application)
    internal val localPairingBridge = LocalPairingAppBridge(
        controller = adbManager.localPairingController,
        serviceLifecycle = AndroidLocalPairingServiceLifecycle(application),
    )
    val deviceProfiles = DataStoreDeviceProfileRepository.create(application)
    val textExporter = SafTextExporter(application)
    val safDocumentStore = SafDocumentStore(application)
    val temporaryDataCleaner = CompositeTemporaryDataCleaner(
        AppTemporaryDataCleaner(application),
        SafTemporaryDataCleaner(safDocumentStore),
    )
}
