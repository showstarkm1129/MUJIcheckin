plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.mujicheckin"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.mujicheckin"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("com.google.android.gms:play-services-location:21.3.0")
}
