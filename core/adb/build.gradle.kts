plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.sheen.adb.core"
    compileSdk = 36

    defaultConfig {
        minSdk = 30
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.all {
            it.useTestNG()
        }
    }
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kadb)
    implementation(libs.okio)
    implementation(libs.apkParser) {
        exclude(group = "com.google.code.findbugs", module = "jsr305")
        exclude(group = "org.bouncycastle", module = "bcprov-jdk15on")
        exclude(group = "org.bouncycastle", module = "bcpkix-jdk15on")
    }

    testImplementation(libs.testng)
}
