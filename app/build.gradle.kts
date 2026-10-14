plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
}

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.aistudio.speedtracker.npxqwt"
    minSdk = 24
    targetSdk = 36
    // Overridable from the command line / CI workflow inputs:
    //   ./gradlew assembleRelease -PversionName=1.1.0 -PversionCode=2
    versionCode = (findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
    versionName = (findProperty("versionName") as String?) ?: "1.0.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  // ---- Signing ---------------------------------------------------------------------
  // ONE certificate for every build — CI release, CI debug, Android Studio. Android only
  // installs an APK *over* an existing install when both carry the same signing
  // certificate; anything else is refused with "package conflicts with an existing
  // package" and forces an uninstall (history lost). Resolution order:
  //   1. KEYSTORE_PATH (+ STORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD) — the CI secret
  //      KEYSTORE_BASE64 decoded by the workflow, or a developer's private upload key;
  //   2. my-upload-key.jks in the project root (git-ignored, private);
  //   3. keystore/ci-shared.p12 — COMMITTED, alias "upload", password "android". This is
  //      the default for GitHub-Actions builds without secrets and for local builds, so
  //      every APK anyone builds from this repo updates the previous one in place.
  //      It is a convenience key, not a secret: for store distribution use 1. or 2.
  val sharedKeystore = rootProject.file("keystore/ci-shared.p12")
  val privateKeystore = rootProject.file("my-upload-key.jks")
  val keystoreFile = System.getenv("KEYSTORE_PATH")?.takeIf { it.isNotBlank() }?.let { rootProject.file(it) }
    ?: privateKeystore.takeIf { it.exists() }
    ?: sharedKeystore
  logger.lifecycle(
    "Signing every build type with ${keystoreFile.name}" +
      if (keystoreFile == sharedKeystore) " (committed shared key)" else " (private key)"
  )

  signingConfigs {
    create("shared") {
      storeFile = keystoreFile
      storePassword = System.getenv("STORE_PASSWORD")?.takeIf { it.isNotBlank() } ?: "android"
      keyAlias = System.getenv("KEY_ALIAS")?.takeIf { it.isNotBlank() } ?: "upload"
      keyPassword = System.getenv("KEY_PASSWORD")?.takeIf { it.isNotBlank() } ?: "android"
      // keytool writes PKCS12 since JDK 9 even for ".jks" names, and the JDK reads both
      // formats either way; the committed key is a real PKCS12 (made with openssl).
      if (keystoreFile.extension.lowercase() in setOf("p12", "pfx")) storeType = "PKCS12"
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("shared")
    }
    debug {
      // Same certificate as release (NOT the per-machine ~/.android/debug.keystore):
      // a debug build from CI or Android Studio installs over a release build and back.
      signingConfig = signingConfigs.getByName("shared")
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }

  buildFeatures {
    compose = true
  }

  testOptions {
    unitTests {
      isIncludeAndroidResources = true
    }
  }
}

dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  implementation(libs.androidx.work.runtime.ktx)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)

  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)

  // The instrumented smoke test drives the (suspend) measurement API directly.
  androidTestImplementation(libs.kotlinx.coroutines.core)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)

  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)

  "ksp"(libs.androidx.room.compiler)
}
