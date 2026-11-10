import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

fun javaString(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val catalogUrl = providers.gradleProperty("tsm.catalog.url").orElse("")
val catalogPublicKey = providers.gradleProperty("tsm.catalog.publicKey").orElse("")
val appVersionName = providers.gradleProperty("tsm.versionName").orElse("0.0.0")

android {
    namespace = "es.empresa.tabletsetup.core"
    compileSdk = 35

    defaultConfig {
        minSdk = 28
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("String", "DEFAULT_CATALOG_URL", javaString(catalogUrl.get().trim()))
        buildConfigField("String", "DEFAULT_CATALOG_PUBLIC_KEY", javaString(catalogPublicKey.get().trim()))
        buildConfigField("String", "SUITE_VERSION", javaString(appVersionName.get()))
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
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
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.core.ktx)
    api(libs.androidx.activity.compose)
    api(libs.androidx.lifecycle.runtime.ktx)
    api(libs.androidx.lifecycle.runtime.compose)
    api(libs.androidx.lifecycle.viewmodel.compose)
    api(libs.androidx.compose.ui)
    api(libs.androidx.compose.ui.graphics)
    api(libs.androidx.compose.foundation)
    api(libs.androidx.compose.material3)
    api(libs.androidx.compose.material.icons.core)
    api(libs.androidx.compose.ui.tooling.preview)
    api(libs.kotlinx.coroutines.android)
    implementation(libs.gson)
    implementation(libs.apksig)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
