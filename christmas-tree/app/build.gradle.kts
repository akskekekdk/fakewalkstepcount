plugins {
    id("com.android.application")
}

android {
    namespace = "com.xmastree.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.xmastree.app"
        minSdk = 26
        targetSdk = 35
        // GitHub 자동 빌드 번호(-PbuildNumber=N)로 버전을 올린다.
        val build = (project.findProperty("buildNumber") as String?)?.toIntOrNull() ?: 0
        versionCode = 1 + build
        versionName = "1.$build"
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
