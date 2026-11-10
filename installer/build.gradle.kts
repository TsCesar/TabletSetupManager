import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Firma release: NUNCA se guardan claves ni contraseñas en el repositorio.
// Se leen de variables de entorno (CI) o de propiedades Gradle locales (~/.gradle/gradle.properties).
fun secret(env: String, prop: String) =
    providers.environmentVariable(env).orElse(providers.gradleProperty(prop))

val releaseKeystore = secret("TSM_RELEASE_KEYSTORE", "tsm.release.keystore")
val releaseStorePassword = secret("TSM_RELEASE_KEYSTORE_PASSWORD", "tsm.release.storePassword")
val releaseKeyAlias = secret("TSM_RELEASE_KEY_ALIAS", "tsm.release.keyAlias")
val releaseKeyPassword = secret("TSM_RELEASE_KEY_PASSWORD", "tsm.release.keyPassword")
val hasReleaseSigning = releaseKeystore.isPresent && releaseStorePassword.isPresent &&
    releaseKeyAlias.isPresent && releaseKeyPassword.isPresent

android {
    namespace = "es.empresa.tabletsetup.installer"
    compileSdk = 35

    defaultConfig {
        applicationId = "es.empresa.tabletsetup.installer"
        minSdk = 28
        targetSdk = 35
        versionCode = providers.gradleProperty("tsm.versionCode").get().toInt()
        versionName = providers.gradleProperty("tsm.versionName").get()
    }

    signingConfigs {
        getByName("debug") {
            // Keystore de depuración COMPARTIDO y público (solo pruebas): permite que las
            // APK debug de distintas compilaciones se actualicen entre sí en las tablets de prueba.
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
}

// ---------------------------------------------------------------------------
// Tablet Updater embebido: la APK del Updater (misma variante y misma firma) se
// incluye en assets/embedded/TabletUpdater.apk para poder instalarlo sin red.
// ---------------------------------------------------------------------------
abstract class EmbedUpdaterApkTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val updaterOutputs: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun embed() {
        val candidates = updaterOutputs.asFileTree.files.filter { it.name.endsWith(".apk") }.sortedBy { it.name }
        val apk = candidates.firstOrNull { !it.name.contains("unsigned") } ?: candidates.firstOrNull()
            ?: throw GradleException("No se encontró la APK de Tablet Updater para embeberla en el Installer")
        if (apk.name.contains("unsigned")) {
            logger.warn("Aviso: se embebe una APK de Updater SIN FIRMA (${apk.name}); no será instalable.")
        }
        val target = outputDir.get().asFile.resolve("embedded")
        target.deleteRecursively()
        target.mkdirs()
        apk.copyTo(target.resolve("TabletUpdater.apk"), overwrite = true)
    }
}

androidComponents {
    onVariants { variant ->
        val buildType = variant.buildType ?: "debug"
        val capitalized = buildType.replaceFirstChar { it.uppercaseChar() }
        val embedTask = tasks.register<EmbedUpdaterApkTask>("embedUpdaterApk${variant.name.replaceFirstChar { it.uppercaseChar() }}") {
            updaterOutputs.from(project(":updater").layout.buildDirectory.dir("outputs/apk/$buildType"))
            dependsOn(":updater:assemble$capitalized")
        }
        variant.sources.assets?.addGeneratedSourceDirectory(embedTask, EmbedUpdaterApkTask::outputDir)
    }
}
