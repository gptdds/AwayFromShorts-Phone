plugins {
    id("com.android.application") version "8.5.2" apply false
    id("com.android.library") version "8.5.2" apply false
    kotlin("android") version "1.9.24" apply false
    kotlin("jvm") version "1.9.24" apply false
}

// Native Android tools and forked JVM classpaths misdecode non-ASCII Windows paths.
// Only generated outputs go to an ASCII cache; sources stay in the user's workspace.
allprojects {
    if (System.getProperty("os.name").startsWith("Windows") && rootDir.path.any { it.code > 127 }) {
        layout.buildDirectory.set(java.io.File(gradle.gradleUserHomeDir,
            "afs-builds/${rootDir.path.hashCode().toUInt()}/${path.replace(':', '/').trim('/').ifEmpty { "root" }}"))
    }
}
