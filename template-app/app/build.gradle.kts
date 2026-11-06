plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.webgen.template"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.webgen.template"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "1.4"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Framework-only on purpose (no AndroidX) so the template can also be
    // built with the manual aapt2 + kotlinc + d8 pipeline (build_template.sh).
}
