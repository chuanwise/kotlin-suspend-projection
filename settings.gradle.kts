pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "kotlin-suspend-projection"

include(":annotations")
include(":runtime-core")
include(":runtime-jvm")
include(":compiler-plugin")
include(":verification-jvm")
include(":gradle-plugin")
