plugins { kotlin("jvm") }
kotlin { jvmToolchain(17) }
java { sourceCompatibility = JavaVersion.VERSION_1_8; targetCompatibility = JavaVersion.VERSION_1_8 }
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach { kotlinOptions.jvmTarget = "1.8" }
dependencies { testImplementation("junit:junit:4.13.2") }
