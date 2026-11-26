plugins {
    id("com.android.application")
}

android {
    namespace = "com.nek0ayaya.gradentranslator"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.nek0ayaya.gradentranslator"
        minSdk = 29
        targetSdk = 35
        versionCode = 3
        versionName = "0.2.1-dev"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
}

dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
    implementation("io.github.libxposed:service:102.0.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
