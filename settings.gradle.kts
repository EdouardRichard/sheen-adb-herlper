pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io") {
            content { includeGroup("com.github.Flyfish233") }
        }
    }
}

rootProject.name = "SheenAdbHelper"
include(":app")
include(":core:adb")
include(":core:data")
include(":core:ui")
include(":feature:devices")
include(":feature:overview")
include(":feature:shell")
include(":feature:processes")
include(":feature:logcat")
include(":feature:settings")
