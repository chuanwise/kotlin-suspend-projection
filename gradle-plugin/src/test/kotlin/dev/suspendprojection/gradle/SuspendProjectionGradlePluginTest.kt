package dev.suspendprojection.gradle

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.jar.JarFile
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

class SuspendProjectionGradlePluginTest {
    @Test
    fun `generic owner receiver visibility and value class matrix compiles`() {
        val projectDir = Files.createTempDirectory("suspend-projection-gradle-test")
        val repository = projectDir.resolve("repository").createDirectories()
        installProjectArtifacts(repository)
        writeProject(projectDir, repository)

        val result = GradleRunner.create()
            .withProjectDir(projectDir.toFile())
            .withTestKitDir(
                Path.of(checkNotNull(System.getProperty("suspendProjection.testKitDir"))).toFile(),
            )
            .withArguments("clean", "build", "verifySuspendProjections", "--stacktrace", "--info")
            .forwardOutput()
            .build()

        assertTrue(result.task(":compileKotlin")?.outcome == TaskOutcome.SUCCESS)
        assertTrue(result.task(":compileJava")?.outcome == TaskOutcome.SUCCESS)
        assertTrue(result.task(":verifySuspendProjections")?.outcome == TaskOutcome.SUCCESS)

        val jar = projectDir.resolve("build/libs/matrix.jar")
        val ownerMethods = readMethodNames(jar, "sample/GenericOwner.class")
        val projectionMethods = readMethodNames(
            jar,
            "sample/GenericOwner\$Projections\$ViaBlocking.class",
        )
        val projectionAccess = readMethodAccesses(
            jar,
            "sample/GenericOwner\$Projections\$ViaBlocking.class",
        )
        assertFalse(ownerMethods.any { it.contains("privateOnlyBlocking") })
        assertFalse(projectionMethods.any { it.contains("privateOnly") })
        assertFalse(hasEntry(jar, "sample/InternalOwner\$Projections.class"))
        assertFalse(hasEntry(jar, "sample/PrivateOwner\$Projections.class"))
        assertFalse(hasEntry(jar, "sample/ProtectedOwner\$Projections.class"))
        assertTrue(projectionMethods.contains("echoBlocking"))
        assertTrue(projectionMethods.contains("mapBlocking"))
        assertTrue(projectionMethods.contains("decorateBlocking"))
        assertTrue(projectionMethods.contains("contextualBlocking"))
        assertTrue(projectionMethods.contains("roundTripBlocking"))
        assertTrue(projectionMethods.contains("nullableRoundTripBlocking"))
        assertTrue(projectionMethods.contains("tokenRoundTripBlocking"))
        assertTrue(projectionMethods.contains("decorateUserBlocking"))
        assertTrue(ownerMethods.contains("roundTrip"))
        assertTrue(ownerMethods.contains("nullableRoundTrip"))
        assertTrue(ownerMethods.contains("tokenRoundTrip"))
        assertTrue(
            projectionMethods
                .filterNot { it == "<clinit>" }
                .all { method ->
                    projectionAccess.getValue(method).all { access ->
                        access and Opcodes.ACC_PUBLIC != 0
                    }
                },
        )
    }

    private fun installProjectArtifacts(repository: Path) {
        val root = Path.of(checkNotNull(System.getProperty("suspendProjection.repoRoot")))
        val version = checkNotNull(System.getProperty("suspendProjection.version"))
        listOf("annotations", "runtime-core", "runtime-jvm", "compiler-plugin", "verification-jvm")
            .forEach { artifact ->
                val module = repository.resolve("dev/suspendprojection/$artifact/$version")
                    .createDirectories()
                Files.copy(
                    root.resolve("$artifact/build/libs/$artifact.jar"),
                    module.resolve("$artifact-$version.jar"),
                    StandardCopyOption.REPLACE_EXISTING,
                )
                module.resolve("$artifact-$version.pom").writeText(
                    """
                    <project xmlns="http://maven.apache.org/POM/4.0.0">
                      <modelVersion>4.0.0</modelVersion>
                      <groupId>dev.suspendprojection</groupId>
                      <artifactId>$artifact</artifactId>
                      <version>$version</version>
                    </project>
                    """.trimIndent(),
                )
            }
    }

    private fun writeProject(projectDir: Path, repository: Path) {
        val root = Path.of(checkNotNull(System.getProperty("suspendProjection.repoRoot")))
        val repositoryUri = repository.toUri().toASCIIString()
        val pluginJar = root.resolve("gradle-plugin/build/libs/gradle-plugin.jar")
            .toUri()
            .toASCIIString()
        projectDir.resolve("settings.gradle.kts").writeText(
            """
            pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
            dependencyResolutionManagement {
                repositories { maven { url = uri("$repositoryUri") }; mavenCentral() }
            }
            rootProject.name = "matrix"
            """.trimIndent(),
        )
        projectDir.resolve("build.gradle.kts").writeText(
            """
            buildscript {
                repositories { mavenCentral(); gradlePluginPortal() }
                dependencies {
                    classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
                    classpath(files(uri("$pluginJar")))
                }
            }

            apply(plugin = "org.jetbrains.kotlin.jvm")
            apply(plugin = "dev.suspendprojection")

            extensions.configure<dev.suspendprojection.gradle.SuspendProjectionExtension> {
                directImplementationEnforcement.set(
                    dev.suspendprojection.gradle.DirectImplementationEnforcement.GUARDED_DEFAULT,
                )
                addDependencies.set(true)
                verifyArtifacts.set(true)
                emitCompatibilityGuard.set(true)
                emitInvalidPathGuard.set(true)
            }
            """.trimIndent(),
        )
        projectDir.resolve("src/main/kotlin/sample/Api.kt").apply {
            parent.createDirectories()
            writeText(
                """
                package sample

                @JvmInline
                value class UserId(val value: String)

                @JvmInline
                value class Token(val value: Int)

                class RequestContext(val requestId: String)

                interface GenericOwner<T> where T : CharSequence, T : Comparable<T> {
                    suspend fun echo(value: T): T
                    suspend fun <R : T> map(value: R): R
                    suspend fun T.decorate(suffix: String): T
                    context(context: RequestContext)
                    suspend fun contextual(value: T): T
                    suspend fun roundTrip(value: UserId): UserId
                    suspend fun nullableRoundTrip(value: UserId?): UserId?
                    suspend fun tokenRoundTrip(value: Token): Token
                    suspend fun UserId.decorateUser(): UserId
                    private suspend fun privateOnly(value: T): T = value
                }

                internal interface InternalOwner {
                    suspend fun hidden(): String
                }

                private interface PrivateOwner {
                    suspend fun hidden(): String
                }

                abstract class ProtectedOwner {
                    protected abstract suspend fun hidden(): String
                }
                """.trimIndent(),
            )
        }
        projectDir.resolve("src/main/java/sample/JavaOwner.java").apply {
            parent.createDirectories()
            writeText(
                """
                package sample;

                public final class JavaOwner
                        implements GenericOwner.Projections.ViaBlocking<JavaOwner.Text> {
                    public static class Text implements CharSequence, Comparable<Text> {
                        private final String value;
                        public Text(String value) { this.value = value; }
                        public int length() { return value.length(); }
                        public char charAt(int index) { return value.charAt(index); }
                        public CharSequence subSequence(int start, int end) {
                            return value.subSequence(start, end);
                        }
                        public int compareTo(Text other) { return value.compareTo(other.value); }
                    }

                    @Override public Text echoBlocking(Text value) { return value; }
                    @Override public <R extends Text> R mapBlocking(R value) { return value; }
                    @Override public Text decorateBlocking(Text receiver, String suffix) {
                        return receiver;
                    }
                    @Override public Text contextualBlocking(
                            RequestContext context,
                            Text value
                    ) { return value; }
                    @Override public String roundTripBlocking(String value) { return value; }
                    @Override public String nullableRoundTripBlocking(String value) {
                        return value;
                    }
                    @Override public int tokenRoundTripBlocking(int value) { return value; }
                    @Override public String decorateUserBlocking(String receiver) {
                        return receiver;
                    }
                }
                """.trimIndent(),
            )
        }
    }

    private fun readMethodNames(jarPath: Path, entryName: String): Set<String> =
        JarFile(jarPath.toFile()).use { jar ->
            val names = linkedSetOf<String>()
            jar.getInputStream(checkNotNull(jar.getJarEntry(entryName))).use { input ->
                ClassReader(input).accept(
                    object : ClassVisitor(Opcodes.ASM9) {
                        override fun visitMethod(
                            access: Int,
                            name: String,
                            descriptor: String,
                            signature: String?,
                            exceptions: Array<out String>?,
                        ): MethodVisitor? {
                            names += name
                            return null
                        }
                    },
                    ClassReader.SKIP_CODE,
                )
            }
            names
        }

    private fun hasEntry(jarPath: Path, entryName: String): Boolean =
        JarFile(jarPath.toFile()).use { it.getJarEntry(entryName) != null }

    private fun readMethodAccesses(jarPath: Path, entryName: String): Map<String, List<Int>> =
        JarFile(jarPath.toFile()).use { jar ->
            val accesses = linkedMapOf<String, MutableList<Int>>()
            jar.getInputStream(checkNotNull(jar.getJarEntry(entryName))).use { input ->
                ClassReader(input).accept(
                    object : ClassVisitor(Opcodes.ASM9) {
                        override fun visitMethod(
                            access: Int,
                            name: String,
                            descriptor: String,
                            signature: String?,
                            exceptions: Array<out String>?,
                        ): MethodVisitor? {
                            accesses.getOrPut(name, ::mutableListOf) += access
                            return null
                        }
                    },
                    ClassReader.SKIP_CODE,
                )
            }
            accesses
        }
}
