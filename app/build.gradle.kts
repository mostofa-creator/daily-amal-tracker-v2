plugins {
    id("com.android.application")
    id("com.google.gms.google-services")
}

android {
    namespace = "com.mostofa.dailyamaltracker"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mostofa.dailyamaltracker"
        minSdk = 23
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
}

dependencies {
    implementation(platform("com.google.firebase:firebase-bom:35.1.0"))

    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
}
