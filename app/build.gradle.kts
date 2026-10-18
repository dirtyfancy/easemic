import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// #E1: one version name per release; the versionCode is derived (same rule as ProductVersion.codeOf).
val easemicVersionName: String = providers.gradleProperty("easemic.versionName").get()
val easemicVersionCode: Int = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""").matchEntire(easemicVersionName)
    ?.destructured?.toList()?.map(String::toInt)
    ?.let { (major, minor, patch) -> major * 1_000_000 + minor * 1_000 + patch }
    ?: error("easemic.versionName must be MAJOR.MINOR.PATCH, got: $easemicVersionName")

android {
    namespace = "com.easemic.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.easemic.app"
        minSdk = 29
        targetSdk = 37
        versionCode = easemicVersionCode
        versionName = easemicVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Orchestrated by tools/run-importer-api-matrix.sh API 31 phases only.
        // Full connected suites (API 36 / Step 7) must not discover these helpers.
        testInstrumentationRunnerArguments["notClass"] =
            "com.easemic.app.acceptance.Task10AcceptanceStateTest"
    }

    signingConfigs {
        create("release") {
            // #E1: the permanent release key. Never commit it: read it from the environment or the git-excluded
            // artifacts/release/signing.properties (storeFile, storePassword). PKCS12 uses one password for both.
            val props = Properties()
            val propsFile = rootProject.file("artifacts/release/signing.properties")
            if (propsFile.isFile) propsFile.inputStream().use { props.load(it) }
            val storePath = System.getenv("EASEMIC_RELEASE_STORE_FILE") ?: props.getProperty("storeFile")
            val password = System.getenv("EASEMIC_RELEASE_STORE_PASSWORD") ?: props.getProperty("storePassword")
            if (storePath != null && password != null) {
                storeFile = rootProject.file(storePath)
                storePassword = password
                keyAlias = "easemic"
                keyPassword = password
                storeType = "PKCS12"
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            // CloudIngestRunner (feature/collect) calls android.util.Log directly —
            // previously that path only ran inside compose UI code and had no unit
            // coverage. Plain JVM unit tests have no real android.jar, so unmocked
            // framework calls throw by default; return defaults instead so Log.*
            // calls are no-ops under test without needing a logging seam.
            isReturnDefaultValues = true
        }
    }

    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")
}

// #E1: a release build without the release key must fail, never produce an APK users could not update later.
gradle.taskGraph.whenReady {
    if (allTasks.any { it.path in setOf(":app:assembleRelease", ":app:packageRelease", ":app:bundleRelease") }) {
        check(android.signingConfigs.getByName("release").storeFile != null) {
            "Release signing is not configured: set EASEMIC_RELEASE_STORE_FILE and EASEMIC_RELEASE_STORE_PASSWORD, " +
                "or create artifacts/release/signing.properties (storeFile, storePassword)."
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", file("$projectDir/schemas").path)
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.coil.network.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.concurrent.futures.ktx)
    implementation(libs.commons.compress)

    implementation(libs.androidx.browser)
    implementation(libs.okhttp)

    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // Real org.json for JVM unit tests (Android stub throws "not mocked").
    testImplementation(libs.org.json)
    // Exercise the real Room repository/generated DAO on the host with a test-only SQLite driver.
    testImplementation(libs.sqlite.jdbc)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.work.testing)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
