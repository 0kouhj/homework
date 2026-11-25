plugins {
    id("com.android.application")
}

android {
    namespace = "com.homework.mobile"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.homework.mobile"
        minSdk = 26
        targetSdk = 36
        versionCode = 13
        versionName = "1.7.4"
        testInstrumentationRunner = "android.test.InstrumentationTestRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    useLibrary("android.test.runner", false)
    useLibrary("android.test.base", false)
}

dependencies {
    implementation("androidx.webkit:webkit:1.13.0")
}
