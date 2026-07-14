package com.sheen.adbhelper

import android.app.Application
import com.sheen.adb.core.AdbManagerProvider
import com.sheen.adb.data.AppTemporaryDataCleaner
import com.sheen.adb.data.DataStoreDeviceProfileRepository
import com.sheen.adb.data.SafTextExporter

class SheenApplication : Application() {
    val container: AppContainer by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { AppContainer(this) }
}

class AppContainer(application: Application) {
    val adbManager = AdbManagerProvider.create(application)
    val deviceProfiles = DataStoreDeviceProfileRepository.create(application)
    val textExporter = SafTextExporter(application)
    val temporaryDataCleaner = AppTemporaryDataCleaner(application)
}
