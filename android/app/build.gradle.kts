plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.meta.inklab"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.meta.inklab"
        minSdk = 29
        targetSdk = 36
        versionCode = 4
        versionName = "0.3.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    val inkVersion = "1.0.0"

    implementation("androidx.ink:ink-nativeloader:$inkVersion")
    implementation("androidx.ink:ink-geometry:$inkVersion")
    implementation("androidx.ink:ink-brush:$inkVersion")
    implementation("androidx.ink:ink-strokes:$inkVersion")
    implementation("androidx.ink:ink-rendering:$inkVersion")
    implementation("androidx.ink:ink-authoring:$inkVersion")
    implementation("androidx.input:input-motionprediction:1.0.0")
    testImplementation("junit:junit:4.13.2")
}
