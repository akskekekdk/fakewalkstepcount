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

    // 모든 빌드를 같은 키로 서명해야 앱 안에서 새 버전으로 업데이트할 수 있다.
    signingConfigs {
        create("shared") {
            storeFile = file("signing/xmas.keystore")
            storePassword = "android"
            keyAlias = "xmas"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
