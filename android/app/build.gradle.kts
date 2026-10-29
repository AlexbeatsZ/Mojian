plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.meta.inklab"
    sourceSets.getByName("main").resources.srcDir("../../shared")
    compileSdk = 36

    defaultConfig {
        applicationId = "com.meta.inklab"
        minSdk = 29
        targetSdk = 36
        versionCode = 14
        versionName = "0.7.6"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0") {
        exclude(group = "org.bouncycastle")
    }
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.1")
}
