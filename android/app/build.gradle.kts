plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.meta.inklab"
    sourceSets.getByName("main").resources.apply {
        srcDir("../../shared")
        include("page-backgrounds.json", "pen-style-presets.json", "pen-style-legacy-migration.json", "fountain-v2.json")
    }
    sourceSets.getByName("test").resources.apply {
        srcDir("../../shared")
        include("fixtures/**", "*.json")
    }
    sourceSets.getByName("test").resources.srcDir("../../docs/examples/pen-configurations")
    compileSdk = 36

    defaultConfig {
        applicationId = "com.meta.inklab"
        minSdk = 29
        targetSdk = 36
        versionCode = 49
        versionName = "0.8.0-material.1"
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
            // Keep development artifacts non-debuggable too: ART debugging slows handwriting.
            isDebuggable = false
        }
        release {
            // Preserve the deployed identity and signer for data-preserving updates. The legacy
            // package suffix is unrelated to this release variant's debuggable flag.
            applicationIdSuffix = ".debug"
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation("com.google.android.material:material:1.14.0")
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
