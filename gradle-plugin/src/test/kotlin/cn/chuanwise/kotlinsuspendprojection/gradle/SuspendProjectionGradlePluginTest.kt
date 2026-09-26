package cn.chuanwise.kotlinsuspendprojection.gradle

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.jar.JarFile
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

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
            "sample/GenericOwner\$Interop\$ViaBlocking.class",
        )
        val projectionAccess = readMethodAccesses(
            jar,
            "sample/GenericOwner\$Interop\$ViaBlocking.class",
        )
        val partialProjectionMethods = readMethodNames(
            jar,
            "sample/PartiallyProjected\$Interop\$ViaBlocking.class",
        )
        assertFalse(ownerMethods.any { it.contains("privateOnlyBlocking") })
        assertFalse(projectionMethods.any { it.contains("privateOnly") })
        assertFalse(hasEntry(jar, "sample/UnannotatedOwner\$Interop.class"))
        assertFalse(hasEntry(jar, "sample/InternalOwner\$Interop.class"))
        assertFalse(hasEntry(jar, "sample/PrivateOwner\$Interop.class"))
        assertFalse(hasEntry(jar, "sample/ProtectedOwner\$Interop.class"))
        assertTrue(hasEntry(jar, "sample/JavaDirectOwner.class"))
        assertTrue(hasEntry(jar, "sample/AsyncApi\$Interop\$ViaCompletionStage.class"))
        assertTrue(hasEntry(jar, "sample/AsyncApi\$Interop\$ViaCompletableFuture.class"))
        assertTrue(hasEntry(jar, "sample/AsyncApi\$Interop\$ViaFuture.class"))
        assertTrue(hasEntry(jar, "sample/JavaAsyncOwners\$Stage.class"))
        assertTrue(hasEntry(jar, "sample/JavaAsyncOwners\$Completable.class"))
        assertTrue(hasEntry(jar, "sample/JavaAsyncOwners\$Legacy.class"))
        assertTrue(hasEntry(jar, "sample/JavaMixedGenericOwners\$Direct.class"))
        assertTrue(hasEntry(jar, "sample/JavaMixedGenericOwners\$Blocking.class"))
        assertTrue(hasEntry(jar, "sample/JavaMixedGenericOwners\$Stage.class"))
        assertTrue(hasEntry(jar, "sample/JavaMixedGenericOwners\$Completable.class"))
        assertTrue(hasEntry(jar, "sample/JavaMixedGenericOwners\$Legacy.class"))
        assertTrue(hasEntry(jar, "sample/FileProjected\$Interop\$ViaFuture.class"))
        assertTrue(hasEntry(jar, "sample/DisabledAtClass\$Interop\$ViaFuture.class"))
        assertTrue(projectionMethods.contains("echoBlocking"))
        assertTrue(projectionMethods.contains("echo"))
        assertTrue(projectionMethods.contains("mapBlocking"))
        assertTrue(projectionMethods.contains("map"))
        assertTrue(projectionMethods.contains("decorateBlocking"))
        assertTrue(projectionMethods.contains("decorate"))
        assertTrue(projectionMethods.contains("contextualBlocking"))
        assertTrue(projectionMethods.contains("contextual"))
        assertTrue(projectionMethods.contains("roundTripBlocking"))
        assertTrue(projectionMethods.contains("roundTrip"))
        assertTrue(projectionMethods.contains("nullableRoundTripBlocking"))
        assertTrue(projectionMethods.contains("nullableRoundTrip"))
        assertTrue(projectionMethods.contains("tokenRoundTripBlocking"))
        assertTrue(projectionMethods.contains("tokenRoundTrip"))
        assertTrue(projectionMethods.contains("decorateUserBlocking"))
        assertTrue(projectionMethods.contains("decorateUser"))
        assertTrue(ownerMethods.contains("roundTrip"))
        assertTrue(ownerMethods.contains("nullableRoundTrip"))
        assertTrue(ownerMethods.contains("tokenRoundTrip"))
        assertTrue(ownerMethods.contains("echoCompletionStage"))
        assertTrue(ownerMethods.contains("echoCompletableFuture"))
        assertTrue(ownerMethods.contains("echoFuture"))
        assertFalse(ownerMethods.contains("mapCompletionStage"))
        assertFalse(ownerMethods.contains("mapCompletableFuture"))
        assertFalse(ownerMethods.contains("mapFuture"))
        val asyncOwnerMethods = readMethodNames(jar, "sample/AsyncApi.class")
        assertTrue(asyncOwnerMethods.contains("loadCompletionStage"))
        assertTrue(asyncOwnerMethods.contains("loadCompletableFuture"))
        assertTrue(asyncOwnerMethods.contains("loadFuture"))
        assertFalse(asyncOwnerMethods.contains("loadBlocking"))
        val fileOwnerMethods = readMethodNames(jar, "sample/FileProjected.class")
        assertTrue(fileOwnerMethods.contains("inheritedFuture"))
        assertFalse(fileOwnerMethods.contains("inheritedBlocking"))
        assertTrue(partialProjectionMethods.contains("selectedBlocking"))
        assertFalse(partialProjectionMethods.contains("skippedBlocking"))
        val disabledMethods = readMethodNames(
            jar,
            "sample/DisabledAtClass\$Interop\$ViaFuture.class",
        )
        assertTrue(disabledMethods.contains("reenabledFuture"))
        assertFalse(disabledMethods.contains("disabledFuture"))
        assertTrue(
            readClassAnnotationClassValues(jar, "sample/GenericOwner.class")
                ["Lkotlin/SubclassOptInRequired;"]
                ?.contains(
                    "Lcn/chuanwise/kotlinsuspendprojection/annotations/" +
                        "UninstrumentedProjectionImplementationError;",
                ) == true,
        )
        assertTrue(
            projectionMethods
                .filterNot { it == "<clinit>" }
                .all { method ->
                    projectionAccess.getValue(method).all { access ->
                        access and Opcodes.ACC_PUBLIC != 0
                    }
                },
        )
        assertMixedGenericSignatures(jar)
    }

    @Test
    fun `blocking primary rejects incomplete Java direct implementation`() {
        val projectDir = Files.createTempDirectory("suspend-projection-strict-test")
        val repository = projectDir.resolve("repository").createDirectories()
        installProjectArtifacts(repository)
        writeStrictFailureProject(projectDir, repository)

        val result = GradleRunner.create()
            .withProjectDir(projectDir.toFile())
            .withTestKitDir(
                Path.of(checkNotNull(System.getProperty("suspendProjection.testKitDir"))).toFile(),
            )
            .withArguments("clean", "compileJava", "--stacktrace")
            .buildAndFail()

        assertTrue(result.task(":compileKotlin")?.outcome == TaskOutcome.SUCCESS)
        assertTrue(result.task(":compileJava")?.outcome == TaskOutcome.FAILED)
        assertTrue(result.output.contains("second"))
    }

    @Test
    fun `completion stage primary supports Kotlin and Java direct implementations`() {
        val projectDir = Files.createTempDirectory("suspend-projection-stage-primary-test")
        val repository = projectDir.resolve("repository").createDirectories()
        installProjectArtifacts(repository)
        writeCompletionStagePrimaryProject(projectDir, repository)

        val result = GradleRunner.create()
            .withProjectDir(projectDir.toFile())
            .withTestKitDir(
                Path.of(checkNotNull(System.getProperty("suspendProjection.testKitDir"))).toFile(),
            )
            .withArguments("clean", "build", "verifySuspendProjections", "--stacktrace")
            .build()

        assertTrue(result.task(":compileKotlin")?.outcome == TaskOutcome.SUCCESS)
        assertTrue(result.task(":compileJava")?.outcome == TaskOutcome.SUCCESS)
        assertTrue(result.task(":verifySuspendProjections")?.outcome == TaskOutcome.SUCCESS)

        val jar = projectDir.resolve("build/libs/stage-primary.jar")
        val methods = readMethodNames(jar, "sample/StageApi.class")
        assertTrue(methods.contains("load"))
        assertFalse(methods.contains("loadCompletionStage"))
        assertTrue(hasEntry(jar, "sample/JavaStageApi.class"))
        assertTrue(hasEntry(jar, "sample/KotlinStageApi.class"))
    }

    private fun installProjectArtifacts(repository: Path) {
        val root = Path.of(checkNotNull(System.getProperty("suspendProjection.repoRoot")))
        val version = checkNotNull(System.getProperty("suspendProjection.version"))
        listOf("annotations", "runtime-core", "runtime-jvm", "compiler-plugin", "verification-jvm")
            .forEach { artifact ->
                val module = repository.resolve("cn/chuanwise/kotlinsuspendprojection/$artifact/$version")
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
                      <groupId>cn.chuanwise.kotlinsuspendprojection</groupId>
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
        val kotlinVersion = checkNotNull(System.getProperty("suspendProjection.kotlinVersion"))
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
                    classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinVersion")
                    classpath(files(uri("$pluginJar")))
                }
            }

            apply(plugin = "org.jetbrains.kotlin.jvm")
            apply(plugin = "cn.chuanwise.kotlinsuspendprojection")

            extensions.configure<cn.chuanwise.kotlinsuspendprojection.gradle.SuspendProjectionExtension> {
                enable = true
                generatedTypes {
                    layout = cn.chuanwise.kotlinsuspendprojection.gradle.GeneratedTypeLayout.NESTED_NAMESPACE
                    namespace = "Interop"
                }
                primary = cn.chuanwise.kotlinsuspendprojection.gradle.JvmProjection.BLOCKING
                jvm {
                    rawSuspendAbi = cn.chuanwise.kotlinsuspendprojection.gradle.RawSuspendAbi.HIDDEN
                    blocking {
                        exports {
                            enable = true
                            emitNamedCaller = false
                        }
                        imports {
                            enable = true
                            execution = cn.chuanwise.kotlinsuspendprojection.gradle.BlockingExecution.DIRECT
                            interruption = cn.chuanwise.kotlinsuspendprojection.gradle.BlockingInterruption.THROW_CHECKED
                        }
                    }
                    runtimeGuards {
                        compatibility = true
                        invalidPath = true
                    }
                }
                dependencies { automatic = true }
                verification { enable = true }
            }
            """.trimIndent(),
        )
        projectDir.resolve("src/main/kotlin/sample/Api.kt").apply {
            parent.createDirectories()
            writeText(
                """
                package sample

                import cn.chuanwise.kotlinsuspendprojection.annotations.JvmSuspendProjection
                import cn.chuanwise.kotlinsuspendprojection.annotations.JvmProjectionType

                @JvmInline
                value class UserId(val value: String)

                @JvmInline
                value class Token(val value: Int)

                class RequestContext(val requestId: String)

                @JvmSuspendProjection
                interface GenericOwner<T> where T : CharSequence, T : Comparable<T> {
                    @JvmSuspendProjection(
                        JvmProjectionType.BLOCKING,
                        JvmProjectionType.COMPLETION_STAGE,
                        JvmProjectionType.COMPLETABLE_FUTURE,
                        JvmProjectionType.FUTURE,
                    )
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

                @JvmSuspendProjection(
                    JvmProjectionType.BLOCKING,
                    JvmProjectionType.COMPLETION_STAGE,
                    JvmProjectionType.COMPLETABLE_FUTURE,
                    JvmProjectionType.FUTURE,
                )
                interface MixedGenericApi<T> where T : CharSequence, T : Comparable<T> {
                    suspend fun <R : T> ownerBound(value: R): R

                    suspend fun <R> independent(owner: T, value: R): R
                        where R : Number, R : Comparable<R>

                    suspend fun <A, B : Number> select(
                        owner: T,
                        first: A,
                        second: B,
                    ): B where A : CharSequence, A : Comparable<A>
                }

                interface PartiallyProjected {
                    @JvmSuspendProjection
                    suspend fun selected(value: String): String
                    suspend fun skipped(value: String): String
                }

                @JvmSuspendProjection(
                    JvmProjectionType.COMPLETION_STAGE,
                    JvmProjectionType.COMPLETABLE_FUTURE,
                    JvmProjectionType.FUTURE,
                )
                interface AsyncApi {
                    suspend fun load(value: String): String
                }

                interface UnannotatedOwner {
                    suspend fun untouched(): String
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
        projectDir.resolve("src/main/kotlin/sample/FilePolicy.kt").writeText(
            """
            @file:cn.chuanwise.kotlinsuspendprojection.annotations.JvmSuspendProjection(
                cn.chuanwise.kotlinsuspendprojection.annotations.JvmProjectionType.FUTURE,
            )

            package sample

            import cn.chuanwise.kotlinsuspendprojection.annotations.JvmSuspendProjection

            interface FileProjected {
                suspend fun inherited(value: String): String
            }

            @JvmSuspendProjection(enable = false)
            interface DisabledAtClass {
                suspend fun disabled(value: String): String

                @JvmSuspendProjection(enable = true)
                suspend fun reenabled(value: String): String
            }
            """.trimIndent(),
        )
        projectDir.resolve("src/main/java/sample/JavaOwner.java").apply {
            parent.createDirectories()
            writeText(
                """
                package sample;

                public final class JavaOwner
                        implements GenericOwner.Interop.ViaBlocking<JavaOwner.Text> {
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
        projectDir.resolve("src/main/java/sample/JavaDirectOwner.java").apply {
            parent.createDirectories()
            writeText(
                """
                package sample;

                public final class JavaDirectOwner
                        implements GenericOwner<JavaOwner.Text> {
                    @Override public JavaOwner.Text echo(JavaOwner.Text value) { return value; }
                    @Override public <R extends JavaOwner.Text> R map(R value) { return value; }
                    @Override public JavaOwner.Text decorate(
                            JavaOwner.Text receiver,
                            String suffix
                    ) { return receiver; }
                    @Override public JavaOwner.Text contextual(
                            RequestContext context,
                            JavaOwner.Text value
                    ) { return value; }
                    @Override public String roundTrip(String value) { return value; }
                    @Override public String nullableRoundTrip(String value) { return value; }
                    @Override public int tokenRoundTrip(int value) { return value; }
                    @Override public String decorateUser(String receiver) { return receiver; }
                }
                """.trimIndent(),
            )
        }
        projectDir.resolve("src/main/java/sample/JavaAsyncOwners.java").apply {
            parent.createDirectories()
            writeText(
                """
                package sample;

                import java.util.concurrent.CompletableFuture;
                import java.util.concurrent.CompletionStage;
                import java.util.concurrent.Future;

                public final class JavaAsyncOwners {
                    public static final class Stage
                            implements AsyncApi.Interop.ViaCompletionStage {
                        @Override public CompletionStage<String> loadCompletionStage(String value) {
                            return CompletableFuture.completedFuture(value);
                        }
                    }

                    public static final class Completable
                            implements AsyncApi.Interop.ViaCompletableFuture {
                        @Override public CompletableFuture<String> loadCompletableFuture(
                                String value
                        ) {
                            return CompletableFuture.completedFuture(value);
                        }
                    }

                    public static final class Legacy
                            implements AsyncApi.Interop.ViaFuture {
                        @Override public Future<String> loadFuture(String value) {
                            return CompletableFuture.completedFuture(value);
                        }
                    }

                    public static void callers(AsyncApi api) {
                        CompletionStage<String> stage = api.loadCompletionStage("stage");
                        CompletableFuture<String> future = api.loadCompletableFuture("future");
                        Future<String> legacy = api.loadFuture("legacy");
                    }
                }
                """.trimIndent(),
            )
        }
        projectDir.resolve("src/main/java/sample/JavaMixedGenericOwners.java").apply {
            parent.createDirectories()
            writeText(
                """
                package sample;

                import java.util.concurrent.CompletableFuture;
                import java.util.concurrent.CompletionStage;
                import java.util.concurrent.Future;

                public final class JavaMixedGenericOwners {
                    public static final class Text
                            implements CharSequence, Comparable<Text> {
                        private final String value;
                        public Text(String value) { this.value = value; }
                        public int length() { return value.length(); }
                        public char charAt(int index) { return value.charAt(index); }
                        public CharSequence subSequence(int start, int end) {
                            return value.subSequence(start, end);
                        }
                        public int compareTo(Text other) {
                            return value.compareTo(other.value);
                        }
                    }

                    public static final class Direct implements MixedGenericApi<Text> {
                        @Override public <R extends Text> R ownerBound(R value) {
                            return value;
                        }

                        @Override public <R extends Number & Comparable<? super R>>
                        R independent(Text owner, R value) {
                            return value;
                        }

                        @Override public <A extends CharSequence & Comparable<? super A>, B extends Number>
                        B select(Text owner, A first, B second) {
                            return second;
                        }
                    }

                    public static final class Blocking
                            implements MixedGenericApi.Interop.ViaBlocking<Text> {
                        @Override public <R extends Text> R ownerBoundBlocking(R value) {
                            return value;
                        }

                        @Override public <R extends Number & Comparable<? super R>>
                        R independentBlocking(Text owner, R value) {
                            return value;
                        }

                        @Override public <A extends CharSequence & Comparable<? super A>, B extends Number>
                        B selectBlocking(Text owner, A first, B second) {
                            return second;
                        }
                    }

                    public static final class Stage
                            implements MixedGenericApi.Interop.ViaCompletionStage<Text> {
                        @Override public <R extends Text>
                        CompletionStage<R> ownerBoundCompletionStage(R value) {
                            return CompletableFuture.completedFuture(value);
                        }

                        @Override public <R extends Number & Comparable<? super R>>
                        CompletionStage<R> independentCompletionStage(Text owner, R value) {
                            return CompletableFuture.completedFuture(value);
                        }

                        @Override public <A extends CharSequence & Comparable<? super A>, B extends Number>
                        CompletionStage<B> selectCompletionStage(Text owner, A first, B second) {
                            return CompletableFuture.completedFuture(second);
                        }
                    }

                    public static final class Completable
                            implements MixedGenericApi.Interop.ViaCompletableFuture<Text> {
                        @Override public <R extends Text>
                        CompletableFuture<R> ownerBoundCompletableFuture(R value) {
                            return CompletableFuture.completedFuture(value);
                        }

                        @Override public <R extends Number & Comparable<? super R>>
                        CompletableFuture<R> independentCompletableFuture(Text owner, R value) {
                            return CompletableFuture.completedFuture(value);
                        }

                        @Override public <A extends CharSequence & Comparable<? super A>, B extends Number>
                        CompletableFuture<B> selectCompletableFuture(Text owner, A first, B second) {
                            return CompletableFuture.completedFuture(second);
                        }
                    }

                    public static final class Legacy
                            implements MixedGenericApi.Interop.ViaFuture<Text> {
                        @Override public <R extends Text> Future<R> ownerBoundFuture(R value) {
                            return CompletableFuture.completedFuture(value);
                        }

                        @Override public <R extends Number & Comparable<? super R>>
                        Future<R> independentFuture(Text owner, R value) {
                            return CompletableFuture.completedFuture(value);
                        }

                        @Override public <A extends CharSequence & Comparable<? super A>, B extends Number>
                        Future<B> selectFuture(Text owner, A first, B second) {
                            return CompletableFuture.completedFuture(second);
                        }
                    }

                    public static <R extends Number & Comparable<? super R>> void callers(
                            MixedGenericApi<Text> api,
                            Text owner,
                            R value
                    ) {
                        R blocking = api.independent(owner, value);
                        CompletionStage<R> stage = api.independentCompletionStage(owner, value);
                        CompletableFuture<R> completable =
                                api.independentCompletableFuture(owner, value);
                        Future<R> legacy = api.independentFuture(owner, value);
                    }
                }
                """.trimIndent(),
            )
        }
    }

    private fun writeStrictFailureProject(projectDir: Path, repository: Path) {
        val root = Path.of(checkNotNull(System.getProperty("suspendProjection.repoRoot")))
        val repositoryUri = repository.toUri().toASCIIString()
        val kotlinVersion = checkNotNull(System.getProperty("suspendProjection.kotlinVersion"))
        val pluginJar = root.resolve("gradle-plugin/build/libs/gradle-plugin.jar")
            .toUri()
            .toASCIIString()
        projectDir.resolve("settings.gradle.kts").writeText(
            """
            pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
            dependencyResolutionManagement {
                repositories { maven { url = uri("$repositoryUri") }; mavenCentral() }
            }
            rootProject.name = "strict-failure"
            """.trimIndent(),
        )
        projectDir.resolve("build.gradle.kts").writeText(
            """
            buildscript {
                repositories { mavenCentral(); gradlePluginPortal() }
                dependencies {
                    classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinVersion")
                    classpath(files(uri("$pluginJar")))
                }
            }

            apply(plugin = "org.jetbrains.kotlin.jvm")
            apply(plugin = "cn.chuanwise.kotlinsuspendprojection")

            extensions.configure<cn.chuanwise.kotlinsuspendprojection.gradle.SuspendProjectionExtension> {
                primary = cn.chuanwise.kotlinsuspendprojection.gradle.JvmProjection.BLOCKING
            }
            """.trimIndent(),
        )
        projectDir.resolve("src/main/kotlin/sample/RequiredApi.kt").apply {
            parent.createDirectories()
            writeText(
                """
                package sample

                import cn.chuanwise.kotlinsuspendprojection.annotations.JvmSuspendProjection

                @JvmSuspendProjection
                interface RequiredApi {
                    suspend fun first(value: String): String
                    suspend fun second(value: String): String
                }
                """.trimIndent(),
            )
        }
        projectDir.resolve("src/main/java/sample/IncompleteJavaApi.java").apply {
            parent.createDirectories()
            writeText(
                """
                package sample;

                public final class IncompleteJavaApi implements RequiredApi {
                    @Override public String first(String value) { return value; }
                }
                """.trimIndent(),
            )
        }
    }

    private fun writeCompletionStagePrimaryProject(projectDir: Path, repository: Path) {
        val root = Path.of(checkNotNull(System.getProperty("suspendProjection.repoRoot")))
        val repositoryUri = repository.toUri().toASCIIString()
        val kotlinVersion = checkNotNull(System.getProperty("suspendProjection.kotlinVersion"))
        val pluginJar = root.resolve("gradle-plugin/build/libs/gradle-plugin.jar")
            .toUri()
            .toASCIIString()
        projectDir.resolve("settings.gradle.kts").writeText(
            """
            pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
            dependencyResolutionManagement {
                repositories { maven { url = uri("$repositoryUri") }; mavenCentral() }
            }
            rootProject.name = "stage-primary"
            """.trimIndent(),
        )
        projectDir.resolve("build.gradle.kts").writeText(
            """
            buildscript {
                repositories { mavenCentral(); gradlePluginPortal() }
                dependencies {
                    classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinVersion")
                    classpath(files(uri("$pluginJar")))
                }
            }

            apply(plugin = "org.jetbrains.kotlin.jvm")
            apply(plugin = "cn.chuanwise.kotlinsuspendprojection")

            extensions.configure<cn.chuanwise.kotlinsuspendprojection.gradle.SuspendProjectionExtension> {
                projections = setOf(
                    cn.chuanwise.kotlinsuspendprojection.gradle.JvmProjection.COMPLETION_STAGE,
                )
                primary = cn.chuanwise.kotlinsuspendprojection.gradle.JvmProjection.COMPLETION_STAGE
            }
            """.trimIndent(),
        )
        projectDir.resolve("src/main/kotlin/sample/StageApi.kt").apply {
            parent.createDirectories()
            writeText(
                """
                package sample

                import cn.chuanwise.kotlinsuspendprojection.annotations.JvmSuspendProjection

                @JvmSuspendProjection
                interface StageApi {
                    suspend fun load(value: String): String
                }

                class KotlinStageApi : StageApi {
                    override suspend fun load(value: String): String = value
                }
                """.trimIndent(),
            )
        }
        projectDir.resolve("src/main/java/sample/JavaStageApi.java").apply {
            parent.createDirectories()
            writeText(
                """
                package sample;

                import java.util.concurrent.CompletableFuture;
                import java.util.concurrent.CompletionStage;

                public final class JavaStageApi implements StageApi {
                    @Override public CompletionStage<String> load(String value) {
                        return CompletableFuture.completedFuture(value);
                    }

                    public static CompletionStage<String> call(StageApi api) {
                        return api.load("value");
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

    private fun assertMixedGenericSignatures(jarPath: Path) {
        val ownerEntry = "sample/MixedGenericApi.class"
        val projections = mapOf(
            "Blocking" to "",
            "CompletionStage" to "Ljava/util/concurrent/CompletionStage",
            "CompletableFuture" to "Ljava/util/concurrent/CompletableFuture",
            "Future" to "Ljava/util/concurrent/Future",
        )

        assertClassSignatureContains(
            jarPath,
            ownerEntry,
            "<T::Ljava/lang/CharSequence;",
            "Ljava/lang/Comparable",
            "TT;",
        )
        projections.forEach { (projection, asyncReturnType) ->
            val entry = "sample/MixedGenericApi\$Interop\$Via$projection.class"
            assertClassSignatureContains(
                jarPath,
                entry,
                "<T::Ljava/lang/CharSequence;",
                "Ljava/lang/Comparable",
                "TT;",
            )

            val suffix = projection
            assertMethodSignatureContains(
                jarPath,
                entry,
                "ownerBound$suffix",
                "<R::TT;>",
                "(TR;)",
                "TR;",
                asyncReturnType,
            )
            assertMethodSignatureContains(
                jarPath,
                entry,
                "independent$suffix",
                "<R:Ljava/lang/Number;",
                "Ljava/lang/Comparable",
                "(TT;TR;)",
                "TR;",
                asyncReturnType,
            )
            assertMethodSignatureContains(
                jarPath,
                entry,
                "select$suffix",
                "<A::Ljava/lang/CharSequence;",
                "B:Ljava/lang/Number;",
                "(TT;TA;TB;)",
                "TB;",
                asyncReturnType,
            )
        }

        assertMethodSignatureContains(
            jarPath,
            ownerEntry,
            "independent",
            "<R:Ljava/lang/Number;",
            "(TT;TR;)",
            "TR;",
        )
        listOf("CompletionStage", "CompletableFuture", "Future").forEach { projection ->
            assertMethodSignatureContains(
                jarPath,
                ownerEntry,
                "independent$projection",
                "<R:Ljava/lang/Number;",
                "(TT;TR;)",
                "TR;",
                "Ljava/util/concurrent/$projection",
            )
        }
    }

    private fun assertClassSignatureContains(
        jarPath: Path,
        entryName: String,
        vararg fragments: String,
    ) {
        val signature = assertNotNull(readClassSignature(jarPath, entryName))
        assertTrue(
            fragments.all(signature::contains),
            "Expected $entryName signature <$signature> to contain ${fragments.toList()}",
        )
    }

    private fun assertMethodSignatureContains(
        jarPath: Path,
        entryName: String,
        methodName: String,
        vararg fragments: String,
    ) {
        val signatures = readMethodSignatures(jarPath, entryName)[methodName].orEmpty()
        assertTrue(
            signatures.any { signature -> fragments.all(signature::contains) },
            "Expected $entryName#$methodName signatures $signatures " +
                "to contain ${fragments.toList()}",
        )
    }

    private fun readClassSignature(jarPath: Path, entryName: String): String? =
        JarFile(jarPath.toFile()).use { jar ->
            var classSignature: String? = null
            jar.getInputStream(checkNotNull(jar.getJarEntry(entryName))).use { input ->
                ClassReader(input).accept(
                    object : ClassVisitor(Opcodes.ASM9) {
                        override fun visit(
                            version: Int,
                            access: Int,
                            name: String,
                            signature: String?,
                            superName: String?,
                            interfaces: Array<out String>?,
                        ) {
                            classSignature = signature
                        }
                    },
                    ClassReader.SKIP_CODE,
                )
            }
            classSignature
        }

    private fun readMethodSignatures(
        jarPath: Path,
        entryName: String,
    ): Map<String, List<String>> = JarFile(jarPath.toFile()).use { jar ->
        val signatures = linkedMapOf<String, MutableList<String>>()
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
                        signature?.let { signatures.getOrPut(name, ::mutableListOf) += it }
                        return null
                    }
                },
                ClassReader.SKIP_CODE,
            )
        }
        signatures
    }

    private fun readClassAnnotationClassValues(
        jarPath: Path,
        entryName: String,
    ): Map<String, List<String>> = JarFile(jarPath.toFile()).use { jar ->
        val values = linkedMapOf<String, MutableList<String>>()
        jar.getInputStream(checkNotNull(jar.getJarEntry(entryName))).use { input ->
            ClassReader(input).accept(
                object : ClassVisitor(Opcodes.ASM9) {
                    override fun visitAnnotation(
                        descriptor: String,
                        visible: Boolean,
                    ) = object : org.objectweb.asm.AnnotationVisitor(Opcodes.ASM9) {
                        override fun visit(name: String?, value: Any?) {
                            if (value is Type) {
                                values.getOrPut(descriptor, ::mutableListOf) += value.descriptor
                            }
                        }
                    }
                },
                ClassReader.SKIP_CODE,
            )
        }
        values
    }
}
