import java.util.Properties

plugins {
    id("com.android.application") version "8.7.3"
    id("org.jetbrains.kotlin.android") version "2.0.21"
}

val localConfig = Properties()
rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { localConfig.load(it) }
val nativeKey = providers.environmentVariable("KAKAO_NATIVE_APP_KEY")
    .orElse(localConfig.getProperty("KAKAO_NATIVE_APP_KEY", ""))

val backendUrl = providers.environmentVariable("WATTUP_API_BASE_URL")
    .orElse(localConfig.getProperty("WATTUP_API_BASE_URL", ""))

android {
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    namespace = "com.wattup.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.wattup.app"
        minSdk = 23
        targetSdk = 35
        resValue("string", "wattup_api_base_url", backendUrl.get())
        versionCode = 1
        versionName = "0.1"
        resValue("string", "kakao_native_app_key", nativeKey.get())
    }
}
dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    debugImplementation("com.google.code.gson:gson:2.10.1")
    implementation("com.kakao.maps.open:android:2.15.2")
}
