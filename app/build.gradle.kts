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
        versionCode = 2
        versionName = "2.0"
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
