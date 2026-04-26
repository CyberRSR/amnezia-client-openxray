plugins {
    id("com.android.application")
}

android {
    namespace = "org.amnezia.oxrayprobe"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "org.amnezia.oxrayprobe"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
}
