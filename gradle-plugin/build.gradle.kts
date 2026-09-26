import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-gradle-plugin`
}

dependencies {
    compileOnly(libs.kotlin.gradle.plugin.api)
    compileOnly(libs.kotlin.gradle.plugin)

    testImplementation(libs.kotlin.test.junit5)
    testImplementation(gradleTestKit())
    testImplementation(libs.asm)
    testRuntimeOnly(libs.junit.platform.launcher)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_1_8)
    }
}

gradlePlugin {
    plugins {
        create("suspendProjection") {
            id = "cn.chuanwise.kotlinsuspendprojection"
            implementationClass =
                "cn.chuanwise.kotlinsuspendprojection.gradle.SuspendProjectionGradlePlugin"
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
    systemProperty("suspendProjection.kotlinVersion", libs.versions.kotlin.get())
    systemProperty(
        "suspendProjection.testKitDir",
        rootProject.layout.projectDirectory.dir(".gradle-test-kit").asFile.absolutePath,
    )
}
