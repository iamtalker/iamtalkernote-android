plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "kr.iamtalker.sttest"
    compileSdk = 34
    defaultConfig { applicationId = "kr.iamtalker.sttest"; minSdk = 26; targetSdk = 34; versionCode = 1; versionName = "0.1"; ndk { abiFilters += listOf("x86_64") } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging { jniLibs { useLegacyPackaging = true } }   // 네이티브 라이브러리를 파일로 풀어 둬야 실행할 수 있다
}
