import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.compile.JavaCompile

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
}

group = "cn.chuanwise.kotlinsuspendprojection"
version = libs.versions.project.get()

subprojects {
    group = rootProject.group
    version = rootProject.version

    apply(plugin = "maven-publish")

    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        if (project.path != ":gradle-plugin") {
            extensions.configure<PublishingExtension> {
                publications.create<MavenPublication>("mavenJava") {
                    from(components.getByName("java"))
                }
            }
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        sourceCompatibility = "1.8"
        targetCompatibility = "1.8"
    }
}
