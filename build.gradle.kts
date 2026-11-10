plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// ---------------------------------------------------------------------------
// Recogida de APK con nombres finales en ./dist (sin Android Studio):
//   ./gradlew collectDebugApks    -> dist/TabletSetupInstaller-debug.apk, dist/TabletUpdater-debug.apk
//   ./gradlew collectReleaseApks  -> dist/TabletSetupInstaller.apk, dist/TabletUpdater.apk (requiere firma release)
// ---------------------------------------------------------------------------
val distDir = layout.projectDirectory.dir("dist")

fun apkDir(module: String, buildType: String) =
    project(module).layout.buildDirectory.dir("outputs/apk/$buildType")

tasks.register<Copy>("collectDebugApks") {
    group = "distribution"
    description = "Compila y copia las APK debug con su nombre final en dist/."
    dependsOn(":installer:assembleDebug", ":updater:assembleDebug")
    from(apkDir(":installer", "debug")) {
        include("*.apk")
        rename { "TabletSetupInstaller-debug.apk" }
    }
    from(apkDir(":updater", "debug")) {
        include("*.apk")
        rename { "TabletUpdater-debug.apk" }
    }
    into(distDir)
}

tasks.register<Copy>("collectReleaseApks") {
    group = "distribution"
    description = "Compila y copia las APK release firmadas con su nombre final en dist/."
    dependsOn(":installer:assembleRelease", ":updater:assembleRelease")
    from(apkDir(":installer", "release")) {
        include("*-release.apk")
        rename { "TabletSetupInstaller.apk" }
    }
    from(apkDir(":updater", "release")) {
        include("*-release.apk")
        rename { "TabletUpdater.apk" }
    }
    into(distDir)
    doLast {
        val produced = listOf("TabletSetupInstaller.apk", "TabletUpdater.apk").filter { distDir.file(it).asFile.exists() }
        if (produced.size != 2) {
            throw GradleException(
                "No se han generado APK release firmadas. Configura TSM_RELEASE_KEYSTORE, " +
                    "TSM_RELEASE_KEYSTORE_PASSWORD, TSM_RELEASE_KEY_ALIAS y TSM_RELEASE_KEY_PASSWORD " +
                    "(ver README, sección 'Firma release')."
            )
        }
    }
}
