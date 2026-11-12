plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.photoclean.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.photoclean.app"
        minSdk = 30          // MediaStore 휴지통 API(createTrashRequest)는 Android 11+
        targetSdk = 35
        // GitHub 자동 빌드 번호(-PbuildNumber=N)로 버전을 올린다. 직접 빌드하면 2.1.0
        val build = (project.findProperty("buildNumber") as String?)?.toIntOrNull() ?: 0
        versionCode = 100 + build
        versionName = "2.1.$build"
    }

    // 모든 빌드를 같은 키로 서명해야 앱 안에서 새 버전으로 업데이트할 수 있다
    signingConfigs {
        create("shared") {
            storeFile = file("signing/photoclean.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
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
    buildFeatures {
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { test ->
            // 스크린샷 테스트용: -Pscreenshots.dir=폴더 를 주면 PNG 저장
            project.findProperty("screenshots.dir")?.let { test.systemProperty("screenshots.dir", it.toString()) }
        }
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
