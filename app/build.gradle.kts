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
        versionCode = 11
        versionName = "0.6.0-beta.6"

        buildConfigField("boolean", "VC_MUMBLE_CORE", vcMumbleCore.toString())

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
