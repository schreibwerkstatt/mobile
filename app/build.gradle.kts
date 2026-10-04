import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Room-Schema-Export (exportSchema=true): versioniertes Schema pro DB-Version unter
// app/schemas/ committen — Grundlage für datenerhaltende Migrationen + MigrationTestHelper.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// Version aus version.properties (einzige Quelle der Wahrheit, siehe CLAUDE.md).
val versionProps = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}
val appVersionName: String = versionProps.getProperty("versionName")
    ?: error("versionName fehlt in version.properties")
val appVersionCode: Int = (versionProps.getProperty("versionCode")
    ?: error("versionCode fehlt in version.properties")).trim().toInt()

// Release-Signing aus keystore.properties (gitignored). Fehlt die Datei (CI, frischer Checkout,
// reine Debug-Builds), bleibt hasReleaseSigning=false und der Release-Build bleibt unsigniert,
// statt den ganzen Build scheitern zu lassen.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
val hasReleaseSigning = keystorePropsFile.exists() &&
    listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
        .all { keystoreProps.getProperty(it)?.isNotBlank() == true }

// Demo-Zugang für den „Demo ausprobieren"-Button im Pairing-Screen (Play-Reviewer).
// Werte aus demo.properties (gitignored — das Repo ist öffentlich, Token gehört nicht hinein;
// Vorlage: demo.properties.template). Fehlt die Datei, bleiben die Felder leer und der
// Button wird ausgeblendet.
val demoPropsFile = rootProject.file("demo.properties")
val demoProps = Properties().apply {
    if (demoPropsFile.exists()) demoPropsFile.inputStream().use { load(it) }
}
val demoServerUrl: String = demoProps.getProperty("demoServerUrl").orEmpty().trim()
val demoDeviceToken: String = demoProps.getProperty("demoDeviceToken").orEmpty().trim()

android {
    namespace = "ch.schreibwerkstatt.mobile"
    compileSdk = 36

    defaultConfig {
        applicationId = "ch.schreibwerkstatt.mobile"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName

        // X-Client-Version-Header (siehe AuthInterceptor) — aus versionName abgeleitet.
        buildConfigField("String", "CLIENT_VERSION", "\"android/$appVersionName\"")

        // GitHub-Repo für die In-App-Update-Prüfung (UpdateChecker liest dort
        // releases/latest). Distributionskanal, NICHT die Mutterprojekt-Server-API.
        buildConfigField("String", "UPDATE_GITHUB_OWNER", "\"schreibwerkstatt\"")
        buildConfigField("String", "UPDATE_GITHUB_REPO", "\"mobile\"")

        // Demo-Zugang (leer = kein Demo-Button, siehe PairingViewModel.demoAvailable).
        buildConfigField("String", "DEMO_SERVER_URL", "\"$demoServerUrl\"")
        buildConfigField("String", "DEMO_DEVICE_TOKEN", "\"$demoDeviceToken\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Vertriebskanal. `github` = Sideload-APK aus GitHub-Releases mit In-App-Update;
    // `play` = Google-Play-Build OHNE Selbst-Update (siehe CLAUDE.md „Vertriebskanäle").
    flavorDimensions += "distribution"
    productFlavors {
        create("github") {
            dimension = "distribution"
            buildConfigField("boolean", "SELF_UPDATE", "true")
        }
        create("play") {
            dimension = "distribution"
            buildConfigField("boolean", "SELF_UPDATE", "false")
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Nur signieren, wenn keystore.properties vorhanden ist (sonst unsignierter Build).
            signingConfig = if (hasReleaseSigning) signingConfigs.getByName("release") else null
        }
        debug {
            isMinifyEnabled = false
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
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    testOptions {
        unitTests {
            // Robolectric braucht Zugriff auf die kompilierten Android-Ressourcen.
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.savedstate)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.retrofit)
    implementation(libs.retrofit.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.browser)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.kotlinx.coroutines.test)
}
