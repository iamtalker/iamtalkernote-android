plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "kr.iamtalker.notes"
    compileSdk = 34

    defaultConfig {
        applicationId = "kr.iamtalker.notes"
        minSdk = 26
        targetSdk = 34
        versionCode = 35
        versionName = "1.4.13"
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
    buildFeatures {
        compose = true
    }
    packaging { jniLibs { useLegacyPackaging = true } }   // 내장 동기화 엔진(libsyncthingnative.so)을 파일로 풀어 둬야 실행할 수 있다
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(bom)
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material")   // 당겨서 새로고침(pullrefresh)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")   // 큐알 찍기(카메라 권한 없이 구글 플레이 서비스가 처리)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")
}
