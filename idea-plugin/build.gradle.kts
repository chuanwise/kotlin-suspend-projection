import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    alias(libs.plugins.intellij.platform)
    java
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdea(libs.versions.intellij.idea)
        bundledPlugin("com.intellij.java")
        bundledPlugin("org.jetbrains.kotlin")
        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.Plugin.Java)
    }
    testImplementation(libs.junit)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

intellijPlatform {
    pluginConfiguration {
        id = "cn.chuanwise.kotlinsuspendprojection.idea"
        name = "Kotlin Suspend Projection"
        version = project.version.toString()

        ideaVersion {
            sinceBuild = "261"
            untilBuild = "262.*"
        }
    }
}

tasks {
    test {
        useJUnit()
    }

    wrapper {
        gradleVersion = "9.1.0"
    }
}
