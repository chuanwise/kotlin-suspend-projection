import org.gradle.api.tasks.compile.JavaCompile

plugins {
    kotlin("jvm") version "2.4.20" apply false
}

group = "dev.suspendprojection"
version = "0.1.0-SNAPSHOT"

subprojects {
    tasks.withType<JavaCompile>().configureEach {
        sourceCompatibility = "1.8"
        targetCompatibility = "1.8"
    }
}
