plugins {
    id("com.android.application")
}

val vcMumbleCore = providers.gradleProperty("vcMumbleCore").orNull == "true"
val vcEmbeddedPlayit = providers.gradleProperty("vcEmbeddedPlayit").orNull == "true"
val vcMumbleRuntimeAar = rootProject.layout.projectDirectory.file(".build/android-stage/vc-mumble-runtime.aar").asFile
val vcPlayitStageDir = rootProject.layout.projectDirectory.dir(".build/playit-android").asFile

android {
    namespace = "com.voicecraft.vcmumbleserver"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.voicecraft.vcmumbleserver"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "0.5.0-beta.1"

        buildConfigField("boolean", "VC_MUMBLE_CORE", vcMumbleCore.toString())
        buildConfigField("boolean", "VC_EMBEDDED_PLAYIT", vcEmbeddedPlayit.toString())

    }

    buildFeatures {
        buildConfig = true
    }

    // Qt 6.8's Android service loader resolves bundled libraries through
    // ApplicationInfo.nativeLibraryDir and System.load(fullPath). Modern AGP
    // normally keeps JNI libraries unextracted inside the APK, leaving that
    // directory empty on-device. Legacy JNI packaging makes Android extract
    // every packaged .so into nativeLibraryDir before Qt starts.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
        }
        getByName("release") {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // Only one service implementation is compiled at a time. The core variant
    // extends QtService so Qt owns runtime/plugin loading; the smoke variant is
    // a normal Android Service around the lightweight TCP/UDP test library.
    sourceSets.getByName("main").java.srcDir(
        if (vcMumbleCore) "src/core/java" else "src/smoke/java"
    )
    if (vcEmbeddedPlayit) {
        // playit executables are Android PIE binaries built from BSD-2-Clause
        // source during CI and intentionally staged as extracted native payloads.
        sourceSets.getByName("main").jniLibs.srcDir(vcPlayitStageDir)
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

if (vcMumbleCore && !vcMumbleRuntimeAar.isFile) {
    throw GradleException(
        "VC Mumble runtime AAR is missing. Run scripts/build-mumble-android-core.sh before Gradle core builds: " +
            vcMumbleRuntimeAar.absolutePath
    )
}

if (vcEmbeddedPlayit) {
    val cli = File(vcPlayitStageDir, "arm64-v8a/libplayit_cli_exec.so")
    val daemon = File(vcPlayitStageDir, "arm64-v8a/libplayitd_exec.so")
    if (!cli.isFile || !daemon.isFile) {
        throw GradleException(
            "Embedded playit payload is missing. Run scripts/build-playit-android-agent.sh before Gradle."
        )
    }
}

dependencies {
    if (vcMumbleCore) {
        implementation(files(vcMumbleRuntimeAar))
    }
}
