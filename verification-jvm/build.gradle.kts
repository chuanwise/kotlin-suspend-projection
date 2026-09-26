import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    application
}

kotlin {
    explicitApi()

    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_1_8)
    }
}

dependencies {
    implementation(project(":annotations"))
    implementation(project(":runtime-core"))
    implementation("org.ow2.asm:asm:9.7.1")
}

application {
    mainClass.set("dev.suspendprojection.verification.SuspendProjectionVerifierCli")
}
