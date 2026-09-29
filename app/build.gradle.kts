import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Untracked, gitignored dev-machine config (SDK paths etc.) - also where the API keys live, so
// they never end up in the repo: PEXELS_API_KEY for the arrival-screen destination photo,
// CARTO_API_KEY / ESRI_API_KEY for the engine's map tiles (passed to the Rust build in
// cargoNdkBuild), and ROOM_SERVER_URL, the base URL of the shared-challenges server (see
// docs/shared-challenges.md); left empty, no sharing UI exists in the build.
val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.silas270.blocktime"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.silas270.blocktime"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Only the ABIs cargoNdkBuild compiles libcesium_rs.so for. Without this, JNA's 32-bit
        // libjnidispatch.so pulls armeabi-v7a/x86 into the bundle, Play serves the app to 32-bit
        // devices, and it crashes on launch looking for the engine library.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        buildConfigField(
            "String",
            "PEXELS_API_KEY",
            "\"${localProperties.getProperty("PEXELS_API_KEY", "")}\""
        )
        buildConfigField(
            "String",
            "ROOM_SERVER_URL",
            "\"${localProperties.getProperty("ROOM_SERVER_URL", "")}\""
        )
    }

    // The Play upload key. Its path and passwords live in local.properties (RELEASE_STORE_FILE,
    // RELEASE_STORE_PASSWORD, RELEASE_KEY_ALIAS, RELEASE_KEY_PASSWORD), never in the repo. A
    // machine without them still builds everything, just with an unsigned release.
    val releaseSigningProperties = listOf(
        "RELEASE_STORE_FILE", "RELEASE_STORE_PASSWORD", "RELEASE_KEY_ALIAS", "RELEASE_KEY_PASSWORD"
    ).associateWith { localProperties.getProperty(it)?.takeIf { value -> value.isNotBlank() } }
    val releaseSigning = if (releaseSigningProperties.values.all { it != null }) {
        signingConfigs.create("release") {
            storeFile = file(releaseSigningProperties.getValue("RELEASE_STORE_FILE")!!)
            storePassword = releaseSigningProperties.getValue("RELEASE_STORE_PASSWORD")
            keyAlias = releaseSigningProperties.getValue("RELEASE_KEY_ALIAS")
            keyPassword = releaseSigningProperties.getValue("RELEASE_KEY_PASSWORD")
        }
    } else {
        null
    }

    buildTypes {
        release {
            signingConfig = releaseSigning
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        prefab = true
        buildConfig = true
    }
    packaging {
        jniLibs {
            // Debug/profiling builds (`-Pcesium.profile=profiling`) embed native debug
            // symbols so on-device simpleperf/Perfetto captures can be symbolicated;
            // without this AGP strips them from the packaged .so even in debug builds.
            keepDebugSymbols += "**/*.so"
        }
    }
    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
    sourceSets {
        // Ships the exported Room schemas (see the `ksp` block below) inside the androidTest APK.
        // `MigrationTestHelper` loads the *old* version's JSON from assets at runtime, so without
        // this every test in MigrationTest fails with "Cannot find the schema file in the assets
        // folder" - which is to say the migrations look verified and are not.
        getByName("androidTest") {
            assets.srcDirs(files("$projectDir/schemas"))
        }
    }
}

tasks.register("cargoNdkBuild") {
    doLast {
        val isWindows = System.getProperty("os.name").lowercase().contains("windows")
        val userHome = System.getProperty("user.home")
        val sdkRoot = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")

        // -Pcesium.profile=profiling builds CesiumRS's `profiling` Cargo profile (release
        // codegen, kept debug symbols) with the `perf_trace` feature on, for on-device
        // CPU/RAM profiling (see tools/run_perf_scenario.sh). Defaults to the normal
        // shipped `release` profile so day-to-day builds are unaffected.
        val cesiumProfile = (project.findProperty("cesium.profile") as String?) ?: "release"

        // CESIUM_RS_HOME lets each dev machine point at its own CesiumRS checkout;
        // falls back to the historical per-machine defaults if unset.
        val absoluteRustPath = System.getenv("CESIUM_RS_HOME") ?: if (isWindows) {
            "c:/Users/kamme/Desktop/CesiumRS"
        } else {
            "$userHome/CesiumRS"
        }
        val targets = mapOf(
            "aarch64-linux-android" to "arm64-v8a",
            "x86_64-linux-android" to "x86_64"
        )

        // rustup installs put cargo in ~/.cargo/bin; a system package (e.g. pacman,
        // apt) puts it on PATH instead - prefer whichever actually exists.
        val rustupCargo = File("$userHome/.cargo/bin/cargo")
        val cargoBin = if (isWindows) "cargo" else if (rustupCargo.exists()) rustupCargo.absolutePath else "cargo"
        // ANDROID_NDK_HOME wins if set; otherwise derive from the SDK root env var
        // (ANDROID_HOME/ANDROID_SDK_ROOT), falling back to the historical
        // per-machine hardcoded paths as a last resort.
        val ndkDir = System.getenv("ANDROID_NDK_HOME")
            ?: sdkRoot?.let { "$it/ndk/27.1.12297006" }
            ?: if (isWindows) {
                "C:/Users/kamme/AppData/Local/Android/Sdk/ndk/30.0.14904198"
            } else {
                "$userHome/android-sdk/ndk/27.1.12297006"
            }

        targets.forEach { (rustTarget, androidAbi) ->
            println("Building Rust library for target: $rustTarget (ABI: $androidAbi)...")
            
            // debug_panel rather than the full "testing" default keeps the test harnesses out
            // of the library; app.rs skips the debug-sliders window on Android, so no dev UI
            // reaches the real app.
            //
            // cesium.profile=profiling swaps in the `profiling` Cargo profile (release
            // codegen, debug symbols kept) plus the `perf_trace` feature (ATrace spans +
            // finer per-subsystem timings); output then lands under a `profiling/` (not
            // `release/`) target directory, matching Cargo's `--profile` naming.
            val cargoFeatures = if (cesiumProfile == "profiling") "debug_panel,perf_trace" else "debug_panel"
            val cargoArgs = if (cesiumProfile == "release") {
                listOf("--release")
            } else {
                listOf("--profile", cesiumProfile)
            }
            val builder = ProcessBuilder(
                listOf(cargoBin, "ndk", "--target", rustTarget, "build", "--lib")
                    + cargoArgs
                    + listOf("--no-default-features", "--features", cargoFeatures)
            )
            builder.directory(File(absoluteRustPath))

            builder.environment()["ANDROID_NDK_HOME"] = ndkDir
            // CesiumRS reads these at compile time: CARTO_API_KEY in `standard_imagery_url()`
            // (without it the dark basemap's tiles come back stamped "API KEY REQUIRED") and
            // ESRI_API_KEY in `satellite_imagery_url()` (without it satellite falls back to
            // Esri's keyless, non-commercial service). Left unset when a property is missing, so
            // a key exported in the shell still reaches cargo.
            listOf("CARTO_API_KEY", "ESRI_API_KEY").forEach { name ->
                localProperties.getProperty(name)?.takeIf { it.isNotBlank() }?.let {
                    builder.environment()[name] = it
                }
            }
            if (!isWindows) {
                builder.environment()["PATH"] = "$userHome/.cargo/bin:" + System.getenv("PATH")
            }

            val logFile = File(absoluteRustPath, "cargo_build.log")
            builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
            builder.redirectError(ProcessBuilder.Redirect.appendTo(logFile))

            val process = builder.start()
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                throw GradleException("cargo ndk build failed with exit code $exitCode. See cargo_build.log in CesiumRS for details.")
            }

            val soFile = File("$absoluteRustPath/target/$rustTarget/$cesiumProfile/libcesium_rs.so")
            val destDir = File(projectDir, "src/main/jniLibs/$androidAbi")
            destDir.mkdirs()
            soFile.copyTo(File(destDir, "libcesium_rs.so"), overwrite = true)
            println("Successfully copied libcesium_rs.so for ABI: $androidAbi")
        }
    }
}

tasks.named("preBuild") {
    dependsOn("cargoNdkBuild")
}

// Room writes one JSON schema per version here, and MigrationTest validates migrations against
// them. These files are committed: a migration can only be tested against the schema it migrates
// *from*, so deleting an old version's JSON makes that migration permanently unverifiable.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation("net.java.dev.jna:jna:5.19.1@aar")
    implementation("androidx.games:games-activity:3.0.4")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-splashscreen:1.2.0")
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.material:material-icons-extended")
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("io.coil-kt.coil3:coil-compose:3.0.4")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.0.4")
    implementation("io.coil-kt.coil3:coil-svg:3.0.4")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation(libs.gson)
    implementation(libs.okhttp)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.room.paging)
    ksp(libs.room.compiler)
    implementation(libs.paging.runtime)
    implementation(libs.paging.compose)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}