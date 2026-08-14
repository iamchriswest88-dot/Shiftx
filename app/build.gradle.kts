import java.util.Properties
import java.io.FileInputStream

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.ksp)
}

android {
    namespace = "com.example.shift"
    compileSdk = 36

    val localProperties = Properties()
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localProperties.load(FileInputStream(localPropertiesFile))
    }

    defaultConfig {
        applicationId = "com.example.shift"
        minSdk = 26
        targetSdk = 30
        // CI passes -PversionCode/-PversionName derived from the release tag so each
        // build is a real upgrade. Local builds fall back to a dev version.
        versionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = (project.findProperty("versionName") as String?) ?: "1.0-dev"

        buildConfigField("String", "GEMINI_API_KEY", "\"${localProperties.getProperty("GEMINI_API_KEY", "")}\"")
    }

    // Checked-in key so every build shares one signing identity: updates install
    // over each other instead of failing with INSTALL_FAILED_UPDATE_INCOMPATIBLE.
    // This is not a secret — it exists to keep app data across upgrades.
    signingConfigs {
        create("shiftx") {
            storeFile = file("shiftx.jks")
            storePassword = "shiftx123"
            keyAlias = "shiftx"
            keyPassword = "shiftx123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("shiftx")
        }
        debug {
            signingConfig = signingConfigs.getByName("shiftx")
        }
    }
    
    lint {
        // lintVital runs on release builds only and treats this as fatal.
        // It is a Google Play distribution rule; this app is sideloaded from
        // GitHub Releases and never published to Play, so it does not apply.
        disable += "ExpiredTargetSdkVersion"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
      compose = true
      aidl = false
      buildConfig = true
      shaders = false
    }

    packaging {
      resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
      }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  implementation("androidx.compose.material:material-icons-extended:1.6.8")
  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Local tests: jUnit, coroutines, Android runner
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)

  // Instrumented tests: jUnit rules and runners
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)

  // Navigation
  implementation("androidx.navigation:navigation-compose:2.7.7")

  
  // Networking
  implementation(libs.retrofit)
  implementation(libs.retrofit.serialization)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.okhttp.logging)

  // Health Connect
  implementation("androidx.health.connect:connect-client:1.1.0-alpha07")

  // Map
  implementation("io.coil-kt:coil-compose:2.5.0")

  // Datastore
  implementation(libs.androidx.datastore.preferences)

  // Room
  implementation(libs.room.runtime)
  implementation(libs.room.ktx)
  ksp(libs.room.compiler)

  // Gemini AI
  implementation(libs.generative.ai)

  // Karoo Extension
  implementation(files("libs/karooext.aar"))
  implementation("com.jakewharton.timber:timber:5.0.1")
}
