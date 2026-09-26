import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    `java-gradle-plugin`
}

dependencies {
    compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin-api:2.4.20")
    compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")

    testImplementation(kotlin("test-junit5"))
    testImplementation(gradleTestKit())
    testImplementation("org.ow2.asm:asm:9.7.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_1_8)
    }
}

gradlePlugin {
    plugins {
        create("suspendProjection") {
            id = "dev.suspendprojection"
            implementationClass = "dev.suspendprojection.gradle.SuspendProjectionGradlePlugin"
            displayName = "Kotlin Suspend Projection"
            description = "Generates Java-facing projections for Kotlin suspend APIs"
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    dependsOn(
        ":annotations:jar",
        ":runtime-core:jar",
        ":runtime-jvm:jar",
        ":compiler-plugin:jar",
        ":verification-jvm:jar",
    )
    systemProperty("suspendProjection.repoRoot", rootProject.projectDir.absolutePath)
    systemProperty("suspendProjection.version", rootProject.version.toString())
    systemProperty(
        "suspendProjection.testKitDir",
        rootProject.layout.projectDirectory.dir(".gradle-test-kit").asFile.absolutePath,
    )
}
