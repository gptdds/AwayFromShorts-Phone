pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
    resolutionStrategy.eachPlugin {
        if (requested.id.id.startsWith("com.android.")) useModule("com.android.tools.build:gradle:${requested.version}")
        if (requested.id.id.startsWith("org.jetbrains.kotlin.")) useModule("org.jetbrains.kotlin:kotlin-gradle-plugin:${requested.version}")
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "AwayFromShorts"
include(":app", ":core:model", ":core:domain", ":core:style-api", ":core:style-manager", ":feature:home", ":style:paper")
include(":style:fluidgradient")
include(":style:spatial3d")
include(":feature:settings")
include(":style:liquidglass")
include(":feature:componentlab")
include(":feature:phase2")
