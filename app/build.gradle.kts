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
    buildTypes {
        getByName("debug") {
            resValue("bool", "demo_server_forecast", providers.environmentVariable("WATTUP_DEMO_FORECAST")
                .orElse(localConfig.getProperty("WATTUP_DEMO_FORECAST", "false")).get().toBoolean().toString())
        }
        getByName("release") { resValue("bool", "demo_server_forecast", "false") }
    }
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
tasks.withType<Test>().configureEach {
    val fixtureUrl = providers.environmentVariable("WATTUP_TEST_FIXTURE_URL").orElse("")
    inputs.property("fixtureUrl", fixtureUrl)
    outputs.upToDateWhen { fixtureUrl.get().isBlank() }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    debugImplementation("com.google.code.gson:gson:2.10.1")
    implementation("com.kakao.maps.open:android:2.15.2")
}
