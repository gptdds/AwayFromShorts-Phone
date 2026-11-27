plugins { id("com.android.library"); kotlin("android") }
android {
    namespace = "com.afs.style.api"
    compileSdk = 34
    defaultConfig { minSdk = 24 }
    sourceSets["main"].java.srcDir(layout.buildDirectory.dir("generated/sharedJava"))
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_1_8; targetCompatibility = JavaVersion.VERSION_1_8 }
    kotlinOptions { jvmTarget = "1.8" }
}
val stageStyleApiJava by tasks.registering(Sync::class) {
    from(rootProject.file("dpc/java")) {
        listOf("DesignTokens", "MotionTokens", "EffectQuality", "StyleRenderer", "RendererFactory", "StyleRegistry", "StyleScreenState", "StyleUiAction", "PointerCoordinates").forEach {
            include("com/afs/guard/style/$it.java")
        }
    }
    into(layout.buildDirectory.dir("generated/sharedJava"))
}
tasks.named("preBuild") { dependsOn(stageStyleApiJava) }
dependencies {
    api(platform("androidx.compose:compose-bom:2024.06.00"))
    api("androidx.compose.foundation:foundation")
    api("androidx.compose.material3:material3")
}
