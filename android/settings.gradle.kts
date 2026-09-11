pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // Lets Gradle auto-download a matching JDK for the `jvmToolchain(17)`
    // requirement below, instead of failing if the local machine only has
    // a different JDK on JAVA_HOME (see app/build.gradle.kts).
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "hisab"
include(":app")
