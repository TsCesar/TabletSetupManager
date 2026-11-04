import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Firma release: NUNCA se guardan claves ni contraseñas en el repositorio.
fun secret(env: String, prop: String) =
    providers.environmentVariable(env).orElse(providers.gradleProperty(prop))

val releaseKeystore = secret("TSM_RELEASE_KEYSTORE", "tsm.release.keystore")
val releaseStorePassword = secret("TSM_RELEASE_KEYSTORE_PASSWORD", "tsm.release.storePassword")
val releaseKeyAlias = secret("TSM_RELEASE_KEY_ALIAS", "tsm.release.keyAlias")
val releaseKeyPassword = secret("TSM_RELEASE_KEY_PASSWORD", "tsm.release.keyPassword")
val hasReleaseSigning = releaseKeystore.isPresent && releaseStorePassword.isPresent &&
    releaseKeyAlias.isPresent && releaseKeyPassword.isPresent

android {
    namespace = "es.empresa.tabletsetup.updater"
    compileSdk = 35

    defaultConfig {
        applicationId = "es.empresa.tabletsetup.updater"
        minSdk = 28
        targetSdk = 35
        versionCode = providers.gradleProperty("tsm.versionCode").get().toInt()
        versionName = providers.gradleProperty("tsm.versionName").get()
    }

    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseKeystore.get())
                storePassword = releaseStorePassword.get()
                keyAlias = releaseKeyAlias.get()
                keyPassword = releaseKeyPassword.get()
            }
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseSigning) signingConfigs.getByName("release") else null
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/versions/9/OSGI-INF/MANIFEST.MF")
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
        disable += setOf(
            "OldTargetApi", "GradleDependency", "NewerVersionAvailable",
            "AndroidGradlePluginVersion", "ObsoleteLintCustomCheck", "MissingTranslation"
        )
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.work.runtime.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.work.testing)
}
