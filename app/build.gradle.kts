import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

val appVersion = Properties().apply {
    file("version.properties").inputStream().use { load(it) }
}
val appVersionCode = appVersion.getProperty("VERSION_CODE").trim().toInt()
val appVersionName = appVersion.getProperty("VERSION_NAME").trim()

/**
 * Release signing. `keystore.properties` (gitignored) wins per field, then
 * `SCANTRADER_KEYSTORE_FILE`, `SCANTRADER_KEYSTORE_PASSWORD`,
 * `SCANTRADER_KEYSTORE_KEY_ALIAS`, `SCANTRADER_KEYSTORE_KEY_PASSWORD`.
 * If any value is missing, the release APK is still built and left unsigned.
 */
fun releaseKeystore(): Map<String, String>? {
    val fromFile = Properties()
    val propsFile = rootProject.file("keystore.properties")
    if (propsFile.isFile) {
        propsFile.inputStream().use { fromFile.load(it) }
    }
    fun value(fileKey: String, envKey: String): String? =
        fromFile.getProperty(fileKey)?.trim()?.takeIf { it.isNotEmpty() }
            ?: System.getenv(envKey)?.trim()?.takeIf { it.isNotEmpty() }

    val storePath = value("storeFile", "SCANTRADER_KEYSTORE_FILE")
    val storePassword = value("storePassword", "SCANTRADER_KEYSTORE_PASSWORD")
    val keyAlias = value("keyAlias", "SCANTRADER_KEYSTORE_KEY_ALIAS")
    val keyPassword = value("keyPassword", "SCANTRADER_KEYSTORE_KEY_PASSWORD")
    if (storePath == null || storePassword == null || keyAlias == null || keyPassword == null) {
        logger.warn(
            "ScanTrader: release APK will be unsigned. Add keystore.properties " +
                "(storeFile, storePassword, keyAlias, keyPassword) or set " +
                "SCANTRADER_KEYSTORE_FILE, SCANTRADER_KEYSTORE_PASSWORD, " +
                "SCANTRADER_KEYSTORE_KEY_ALIAS and SCANTRADER_KEYSTORE_KEY_PASSWORD.",
        )
        return null
    }
    val candidate = file(storePath).let { if (it.isAbsolute) it else rootProject.file(storePath) }
    if (!candidate.isFile) {
        logger.warn("ScanTrader: keystore not found at ${candidate.absolutePath}. Release APK will be unsigned.")
        return null
    }
    return mapOf(
        "storeFile" to candidate.absolutePath,
        "storePassword" to storePassword,
        "keyAlias" to keyAlias,
        "keyPassword" to keyPassword,
    )
}

val releaseSigning = releaseKeystore()

/** Token CardTrader di questa build. Sta in `local.properties` (ignorato da git), non nel sorgente. */
val cardTraderToken = run {
    val local = Properties()
    val localFile = rootProject.file("local.properties")
    if (localFile.isFile) localFile.inputStream().use { local.load(it) }
    (local.getProperty("cardtrader.token") ?: findProperty("scantrader.cardTraderToken") as? String)
        ?.trim()
        .orEmpty()
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
}
val githubSelfUpdate = (findProperty("scantrader.githubSelfUpdate") as? String)
    ?.equals("false", ignoreCase = true)
    ?.not()
    ?: true

android {
    namespace = "com.scantrader.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.scantrader.app"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        // Play builds pass -Pscantrader.githubSelfUpdate=false. Debug stays `assembleDebug`.
        buildConfigField("boolean", "GITHUB_SELF_UPDATE", githubSelfUpdate.toString())
        // Gallerie visive dei giochi CardTrader (`<indirizzo><gioco>.stcat`). Vuoto: nessuna galleria scaricata.
        buildConfigField("String", "CT_GALLERY_URL", "\"" + (project.findProperty("scantrader.ctGalleryUrl") ?: "") + "\"")
        buildConfigField("String", "CARDTRADER_TOKEN", "\"$cardTraderToken\"")
    }

    signingConfigs {
        if (releaseSigning != null) {
            create("release") {
                storeFile = file(releaseSigning.getValue("storeFile"))
                storePassword = releaseSigning.getValue("storePassword")
                keyAlias = releaseSigning.getValue("keyAlias")
                keyPassword = releaseSigning.getValue("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            if (releaseSigning != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Phones. Debug keeps every ABI so an x86_64 emulator still runs.
            ndk {
                abiFilters += "arm64-v8a"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.exifinterface)
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp.logging)
    implementation(libs.coil.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.mlkit.text.recognition)
    implementation(libs.mlkit.text.recognition.chinese)
    implementation(libs.mlkit.text.recognition.japanese)
    implementation(libs.onnxruntime.android)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(libs.junit)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
}
