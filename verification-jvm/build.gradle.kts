import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
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
    implementation(libs.asm)
}

application {
    mainClass.set("cn.chuanwise.kotlinsuspendprojection.verification.SuspendProjectionVerifierCli")
}
