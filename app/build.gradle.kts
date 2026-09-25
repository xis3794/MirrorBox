import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.xis3794.mirrorbox"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.xis3794.mirrorbox"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("release") {
            val props = rootProject.file("keys/keystore.properties")
            if (props.exists()) {
                val p = Properties()
                props.inputStream().use { p.load(it) }
                storeFile = rootProject.file(p.getProperty("storeFile"))
                storePassword = p.getProperty("storePassword")
                keyAlias = p.getProperty("keyAlias")
                keyPassword = p.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (rootProject.file("keys/keystore.properties").exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // Required so bundled native tools are extracted to nativeLibraryDir and can be exec'd
            useLegacyPackaging = true
        }
    }

    // Per-ABI APKs keep the download small (the toolchain is ~15 MB per ABI); a universal
    // APK is still produced for convenience.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }
}

// NOTE (ARM64 / proot development environments):
// AGP resolves its own `com.android.tools.build:aapt2` artifact, and the Maven copy for
// linux is x86_64 only — it cannot start on an aarch64 host ("AAPT2 daemon startup failed").
// The repository's setup_android_env.sh installs an ARM64 aapt2 into build-tools and points
// AGP at it through `android.aapt2FromMavenOverride` (written to ~/.gradle/gradle.properties),
// so no build-script hack is needed here anymore.

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.core)
    implementation(libs.androidx.documentfile)
    implementation(project(":qcow2"))

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
