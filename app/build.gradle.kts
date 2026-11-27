import java.io.File
import java.util.Properties

plugins { id("com.android.application"); kotlin("android") }

// Both build paths read the same version; only debug adds a development label.
val legacyBuild = rootProject.file("dpc/build.sh").readText()
val releaseCode = (System.getenv("AFS_VERSION_CODE")?.takeIf { it.isNotBlank() }
    ?: Regex("AFS_VERSION_CODE:-\\s*(\\d+)").find(legacyBuild)!!.groupValues[1]).toInt()
val releaseName = System.getenv("AFS_VERSION_NAME")?.takeIf { it.isNotBlank() }
    ?: Regex("AFS_VERSION_NAME:-\\s*([\\d.]+)").find(legacyBuild)!!.groupValues[1]
val maintenance = System.getenv("AFS_RECOVERY") == "1"
val privateKeystore = System.getenv("AFS_KEYSTORE")?.takeIf { it.isNotBlank() }
val universeDemo = System.getenv("AFS_UNIVERSE_DEMO") == "1"

android {
    namespace = "com.afs.guard"
    compileSdk = 34
    defaultConfig {
        applicationId = if (universeDemo) "com.afs.guard.universe" else "com.afs.guard"
        minSdk = 24
        targetSdk = 34
        versionCode = releaseCode
        versionName = releaseName
        testApplicationId = if (universeDemo) "com.afs.guard.universe.test" else "com.afs.guard.test"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "PREFILL_TOKEN", "\"\"")
        buildConfigField("boolean", "PLUGIN_TEST_MODE", "false")
        buildConfigField("boolean", "PLUGIN_STATS_LEGACY", "false")
        buildConfigField("String", "PLUGIN_TRUST_SHA256", "\"${rootProject.file("dpc/release/signing-certificate.sha256").readText().trim()}\"")
    }
    // One copy of the deployed business core; no generated dpc/build classes or credentials.
    sourceSets["main"].apply {
        manifest.srcFile(layout.buildDirectory.file("generated/legacyManifest/AndroidManifest.xml"))
        java.srcDirs("src/main/java")
        java.srcDir(layout.buildDirectory.dir("generated/legacyJava"))
        java.srcDir(layout.buildDirectory.dir("generated/legacyAidl"))
        res.srcDirs("../dpc/res")
        assets.srcDirs("../dpc/assets")
    }
    buildFeatures { compose = true; buildConfig = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_1_8; targetCompatibility = JavaVersion.VERSION_1_8 }
    kotlinOptions { jvmTarget = "1.8" }
    if (rootProject.file("dpc/keystore/afs.keystore").isFile) signingConfigs.getByName("debug") {
        storeFile = rootProject.file("dpc/keystore/afs.keystore")
        storePassword = "afsguard"; keyAlias = "afs"; keyPassword = "afsguard"
    }
    if (privateKeystore != null) {
        signingConfigs.create("externalDebug") {
            storeFile = file(privateKeystore)
            storePassword = System.getenv("AFS_STORE_PASS")
            keyAlias = System.getenv("AFS_KEY_ALIAS")
            keyPassword = System.getenv("AFS_KEY_PASS") ?: System.getenv("AFS_STORE_PASS")
        }
        signingConfigs.create("externalRelease") {
            storeFile = file(privateKeystore)
            storePassword = System.getenv("AFS_STORE_PASS")
            keyAlias = System.getenv("AFS_KEY_ALIAS")
            keyPassword = System.getenv("AFS_KEY_PASS") ?: System.getenv("AFS_STORE_PASS")
        }
    }
    buildTypes {
        getByName("debug") {
            versionNameSuffix = "-compose-dev"
            if (privateKeystore != null
                && System.getenv("AFS_DEBUG_EXTERNAL_SIGN") == "1") {
                signingConfig = signingConfigs.getByName("externalDebug")
            }
        }
        getByName("release") {
            isDebuggable = false
            if (privateKeystore != null) signingConfig = signingConfigs.getByName("externalRelease")
        }
    }
    packaging { resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*") }
}
val prepareLegacyManifest by tasks.registering {
    val source = rootProject.file("dpc/AndroidManifest.xml")
    val output = layout.buildDirectory.file("generated/legacyManifest/AndroidManifest.xml")
    inputs.file(source); outputs.file(output)
    inputs.property("maintenance", maintenance)
    inputs.property("universeDemo", universeDemo)
    doLast {
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val document = factory.newDocumentBuilder().parse(source)
        document.documentElement.removeAttribute("package")
        if (universeDemo) {
            val application = document.getElementsByTagName("application").item(0) as org.w3c.dom.Element
            application.setAttributeNS("http://schemas.android.com/apk/res/android", "android:label", "AFS Visual Demo")
            val providers = document.getElementsByTagName("provider")
            for (index in 0 until providers.length) {
                val provider = providers.item(index) as org.w3c.dom.Element
                val authority = provider.getAttributeNS("http://schemas.android.com/apk/res/android", "authorities")
                provider.setAttributeNS("http://schemas.android.com/apk/res/android", "android:authorities", authority.replace("com.afs.guard", "com.afs.guard.universe"))
            }
        }
        if (maintenance) {
            check(System.getenv("AFS_RELEASE") == "1") { "Maintenance requires verified release signing" }
            val application = document.getElementsByTagName("application").item(0) as org.w3c.dom.Element
            application.setAttributeNS("http://schemas.android.com/apk/res/android", "android:label", "AFS Maintenance")
            val receiver = document.createElement("receiver")
            receiver.setAttributeNS("http://schemas.android.com/apk/res/android", "android:name", ".RecoveryReceiver")
            receiver.setAttributeNS("http://schemas.android.com/apk/res/android", "android:exported", "true")
            receiver.setAttributeNS("http://schemas.android.com/apk/res/android", "android:permission", "android.permission.DUMP")
            application.appendChild(receiver)
        }
        val activity = document.createElement("activity")
        activity.setAttributeNS("http://schemas.android.com/apk/res/android", "android:name", "com.afs.guard.SharedHomeActivity")
        activity.setAttributeNS("http://schemas.android.com/apk/res/android", "android:exported", "false")
        if (universeDemo) {
            activity.setAttributeNS("http://schemas.android.com/apk/res/android", "android:exported", "true")
            val filters = document.getElementsByTagName("intent-filter")
            val launcherFilters = (0 until filters.length).map { filters.item(it) }.filter { filter ->
                val actions = (filter as org.w3c.dom.Element).getElementsByTagName("action")
                (0 until actions.length).any { (actions.item(it) as org.w3c.dom.Element).getAttributeNS("http://schemas.android.com/apk/res/android", "name") == "android.intent.action.MAIN" }
            }
            launcherFilters.forEach { it.parentNode.removeChild(it) }
            val filter = document.createElement("intent-filter")
            listOf("action" to "android.intent.action.MAIN", "category" to "android.intent.category.LAUNCHER").forEach { (tag, name) ->
                val element = document.createElement(tag)
                element.setAttributeNS("http://schemas.android.com/apk/res/android", "android:name", name)
                filter.appendChild(element)
            }
            activity.appendChild(filter)
            val elements = document.getElementsByTagName("*")
            for (index in 0 until elements.length) {
                val element = elements.item(index) as org.w3c.dom.Element
                for (attribute in listOf("name", "permission")) {
                    val value = element.getAttributeNS("http://schemas.android.com/apk/res/android", attribute)
                    if (value.startsWith("com.afs.guard.permission.")) element.setAttributeNS("http://schemas.android.com/apk/res/android", "android:$attribute", value.replace("com.afs.guard.permission.", "com.afs.guard.universe.permission."))
                }
            }
        }
        document.getElementsByTagName("application").item(0).appendChild(activity)
        val phase2 = document.createElement("activity")
        phase2.setAttributeNS("http://schemas.android.com/apk/res/android", "android:name", "com.afs.guard.Phase2PreviewActivity")
        phase2.setAttributeNS("http://schemas.android.com/apk/res/android", "android:exported", if (universeDemo) "true" else "false")
        document.getElementsByTagName("application").item(0).appendChild(phase2)
        if (universeDemo) {
            val lab = document.createElement("activity")
            lab.setAttributeNS("http://schemas.android.com/apk/res/android", "android:name", "com.afs.guard.ComponentLabActivity")
            lab.setAttributeNS("http://schemas.android.com/apk/res/android", "android:exported", "true")
            lab.setAttributeNS("http://schemas.android.com/apk/res/android", "android:windowSoftInputMode", "adjustResize")
            val filters = activity.getElementsByTagName("intent-filter")
            while (filters.length > 0) lab.appendChild(filters.item(0))
            activity.setAttributeNS("http://schemas.android.com/apk/res/android", "android:exported", "false")
            document.getElementsByTagName("application").item(0).appendChild(lab)
        }
        val provider = document.createElement("provider")
        provider.setAttributeNS("http://schemas.android.com/apk/res/android", "android:name", "com.afs.guard.StylePreferencesProvider")
        provider.setAttributeNS("http://schemas.android.com/apk/res/android", "android:authorities", if (universeDemo) "com.afs.guard.universe.visual-preferences" else "com.afs.guard.visual-preferences")
        provider.setAttributeNS("http://schemas.android.com/apk/res/android", "android:exported", "false")
        document.getElementsByTagName("application").item(0).appendChild(provider)
        val target = output.get().asFile; target.parentFile.mkdirs()
        javax.xml.transform.TransformerFactory.newInstance().newTransformer().transform(
            javax.xml.transform.dom.DOMSource(document), javax.xml.transform.stream.StreamResult(target))
    }
}
val prepareLegacyAidl by tasks.registering {
    val sources = rootProject.file("plugin-api/aidl")
    val output = layout.buildDirectory.dir("generated/legacyAidl")
    inputs.dir(sources); outputs.dir(output)
    doLast {
        // Native aidl dependency files use the Windows code page for Chinese paths.
        // Stage only the public AIDL in an ASCII temp directory, then copy Java back.
        val temp = File(System.getProperty("java.io.tmpdir")).canonicalFile
        val stage = File(temp, "afs-aidl-${rootProject.projectDir.path.hashCode().toUInt()}").canonicalFile
        require(stage.parentFile == temp && stage.name.startsWith("afs-aidl-"))
        val input = File(stage, "input"); val generated = File(stage, "output")
        input.deleteRecursively(); generated.deleteRecursively(); generated.mkdirs()
        sources.copyRecursively(input, overwrite = true)
        val sdk = Properties().apply { rootProject.file("local.properties").inputStream().use { load(it) } }.getProperty("sdk.dir")
        val executable = File(sdk, "build-tools/34.0.0/aidl.exe")
        input.walkTopDown().filter { it.extension == "aidl" }.forEach { aidl ->
            val process = ProcessBuilder(executable.path, "-I${input.path}", "-o${generated.path}", aidl.path).redirectErrorStream(true).start()
            val message = process.inputStream.readBytes().toString(Charsets.UTF_8)
            check(process.waitFor() == 0) { message }
        }
        val target = output.get().asFile.canonicalFile
        require(target.toPath().startsWith(layout.buildDirectory.get().asFile.canonicalFile.toPath()))
        target.deleteRecursively()
        generated.copyRecursively(target, overwrite = true)
    }
}
val stageLegacyJava by tasks.registering(Sync::class) {
    from(rootProject.file("dpc/java")) {
        listOf("DesignTokens", "MotionTokens", "EffectQuality", "StyleRenderer", "RendererFactory", "StyleRegistry", "StyleScreenState", "StyleUiAction", "PointerCoordinates").forEach {
            exclude("com/afs/guard/style/$it.java")
        }
    }
    from(rootProject.file("core/rules/src/main/java"))
    from(rootProject.file("plugin-api/java"))
    into(layout.buildDirectory.dir("generated/legacyJava"))
}
tasks.named("preBuild") { dependsOn(prepareLegacyManifest, prepareLegacyAidl, stageLegacyJava) }
tasks.configureEach {
    if (name == "preReleaseBuild") doFirst {
        check(privateKeystore != null && System.getenv("AFS_RELEASE") == "1") {
            "Release requires external signing; use dpc/release/build-release.ps1 -Compose"
        }
        check(!file(privateKeystore).canonicalFile.toPath().startsWith(rootProject.projectDir.canonicalFile.toPath())) {
            "Release signing must be outside the repository"
        }
        check(System.getenv("AFS_PREFILL").let { it == null || it == "0" }
            && System.getenv("AFS_PLUGIN_TEST_MODE") != "1") { "Release cannot embed credentials or test trust" }
    }
}
tasks.register<Copy>("exportComposeArtifacts") {
    dependsOn("assembleDebug", "assembleDebugAndroidTest")
    from(layout.buildDirectory.file("outputs/apk/debug/app-debug.apk"))
    from(layout.buildDirectory.file("outputs/apk/androidTest/debug/app-debug-androidTest.apk"))
    into(rootProject.layout.projectDirectory.dir("build/compose-phase2"))
}
tasks.register<Copy>("exportSignedArtifacts") {
    dependsOn("assembleRelease")
    from(layout.buildDirectory.file("outputs/apk/release/app-release.apk")) { rename { "afs-guard.apk" } }
    into(rootProject.layout.projectDirectory.dir("build/compose-release"))
}
dependencies {
    implementation(project(":feature:componentlab"))
    implementation(project(":feature:phase2"))
    implementation(project(":feature:home"))
    implementation(project(":feature:settings"))
    implementation(project(":core:domain"))
    implementation(project(":core:style-manager"))
    implementation(project(":core:style-api"))
    implementation(project(":style:paper"))
    implementation(project(":style:fluidgradient"))
    implementation(project(":style:liquidglass"))
    implementation(project(":style:spatial3d"))
    implementation(files("../dpc/libs/pdfbox-android-2.0.27.0.jar"))
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.compose.material3:material3")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.06.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("junit:junit:4.13.2")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
