plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.umer.lifeai"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.umer.lifeai"; minSdk = 26; targetSdk = 33
        // CI passes -PverCode=<run number> so every build installs OVER the previous one
        versionCode = (project.findProperty("verCode") as String?)?.toIntOrNull() ?: 1
        versionName = "2.0.${project.findProperty("verCode") ?: 0}"
    }
    // Fixed keystore committed to the repo: GitHub runners would otherwise sign with a random key each run
    // and Android refuses to update an app signed with a different key.
    signingConfigs {
        create("fixed") { storeFile = file("lifeai-debug.jks"); storePassword = "lifeai123"; keyAlias = "lifeai"; keyPassword = "lifeai123" }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("fixed") }
        getByName("release") { signingConfig = signingConfigs.getByName("fixed"); isMinifyEnabled = false }
    }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    lint { abortOnError = false; checkReleaseBuilds = false }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-service:2.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")   // QR pairing, no camera permission needed
    testImplementation("junit:junit:4.13.2")
}
