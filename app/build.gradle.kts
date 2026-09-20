plugins {
    id("com.android.application")
}

android {
    namespace = "com.wooil.valuebandscanner"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.wooil.valuebandscanner"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "1.0.5"
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
}
