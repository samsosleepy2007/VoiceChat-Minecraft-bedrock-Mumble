plugins {
    id("com.android.application")
}

val vcMumbleCore = providers.gradleProperty("vcMumbleCore").orNull == "true"
val vcMumbleRuntimeAar = rootProject.layout.projectDirectory.file(".build/android-stage/vc-mumble-runtime.aar").asFile

android {
    namespace = "com.voicecraft.vcmumbleserver"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.voicecraft.vcmumbleserver"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.4.0-alpha03-smoke"

        buildConfigField("boolean", "VC_MUMBLE_CORE", vcMumbleCore.toString())

    }

    buildFeatures {
        buildConfig = true
    }

    if (vcMumbleCore) {
        // Qt 6.8's loader checks nativeLibraryDir and loads absolute file paths.
        // The host APK must extract the AAR's libraries, as Qt's own app does.
        packaging.jniLibs.useLegacyPackaging = true
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

dependencies {
    if (vcMumbleCore) {
        implementation(files(vcMumbleRuntimeAar))
    }
}
