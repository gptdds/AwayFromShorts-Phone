plugins { id("com.android.library"); kotlin("android") }
android {
    namespace = "com.afs.feature.settings"
    compileSdk = 34
    defaultConfig { minSdk = 24 }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_1_8; targetCompatibility = JavaVersion.VERSION_1_8 }
    kotlinOptions { jvmTarget = "1.8" }
}
dependencies {
    implementation(project(":core:style-api"))
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")
}
