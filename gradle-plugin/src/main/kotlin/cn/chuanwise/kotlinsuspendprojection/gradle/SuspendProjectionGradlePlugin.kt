package cn.chuanwise.kotlinsuspendprojection.gradle

import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.compile.JavaCompile
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmCompilerOptions
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption

public class SuspendProjectionGradlePlugin : KotlinCompilerPluginSupportPlugin {
    override fun apply(target: Project) {
        val extension = target.extensions.create(
            EXTENSION_NAME,
            SuspendProjectionExtension::class.java,
        )
        val verifierClasspath = target.configurations.create(VERIFIER_CONFIGURATION).apply {
            isCanBeConsumed = false
            isCanBeResolved = true
            isVisible = false
        }

        target.tasks.withType(JavaCompile::class.java).configureEach { task ->
            task.sourceCompatibility = "1.8"
            task.targetCompatibility = "1.8"
        }

        target.pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
            target.afterEvaluate {
                extension.validate()
                if (extension.dependencies.automatic) {
                    target.dependencies.add("compileOnly", "$GROUP:$ANNOTATIONS_ARTIFACT:$VERSION")
                    target.dependencies.add("implementation", "$GROUP:$RUNTIME_CORE_ARTIFACT:$VERSION")
                    target.dependencies.add("implementation", "$GROUP:$RUNTIME_JVM_ARTIFACT:$VERSION")
                }
            }
        }

        target.dependencies.add(VERIFIER_CONFIGURATION, "$GROUP:$VERIFIER_ARTIFACT:$VERSION")
        target.dependencies.add(VERIFIER_CONFIGURATION, "$GROUP:$ANNOTATIONS_ARTIFACT:$VERSION")
        target.dependencies.add(VERIFIER_CONFIGURATION, "$GROUP:$RUNTIME_CORE_ARTIFACT:$VERSION")
        target.dependencies.add(
            VERIFIER_CONFIGURATION,
            "org.jetbrains.kotlin:kotlin-stdlib:$KOTLIN_VERSION",
        )
        target.dependencies.add(VERIFIER_CONFIGURATION, "org.ow2.asm:asm:9.7.1")

        val verify = target.tasks.register(
            VERIFY_TASK_NAME,
            VerifySuspendProjectionsTask::class.java,
        ) { task ->
            task.group = "verification"
            task.description = "Verifies generated suspend projection bytecode and metadata."
            task.verifier.from(verifierClasspath)
            task.onlyIf { extension.verification.enable }
        }
        target.pluginManager.withPlugin("java") {
            val jar = target.tasks.named("jar")
            verify.configure { task ->
                task.dependsOn(jar)
                task.artifacts.from(jar)
            }
        }
        target.tasks.matching { it.name == "check" }.configureEach { it.dependsOn(verify) }
    }

    override fun isApplicable(kotlinCompilation: KotlinCompilation<*>): Boolean {
        val extension = kotlinCompilation.target.project.extensions
            .getByType(SuspendProjectionExtension::class.java)
        return extension.enable && kotlinCompilation.platformType.name == "jvm"
    }

    override fun getCompilerPluginId(): String = COMPILER_PLUGIN_ID

    override fun getPluginArtifact(): SubpluginArtifact = SubpluginArtifact(
        groupId = GROUP,
        artifactId = COMPILER_ARTIFACT,
        version = VERSION,
    )

    override fun applyToCompilation(
        kotlinCompilation: KotlinCompilation<*>,
    ): Provider<List<SubpluginOption>> {
        val project = kotlinCompilation.target.project
        val extension = project.extensions.getByType(SuspendProjectionExtension::class.java)
        extension.validate()
        kotlinCompilation.compileTaskProvider.configure { task ->
            (task.compilerOptions as? KotlinJvmCompilerOptions)?.apply {
                jvmTarget.set(JvmTarget.JVM_1_8)
                jvmDefault.set(JvmDefaultMode.NO_COMPATIBILITY)
                when (extension.jvm.directImplementation.uninstrumentedKotlin) {
                    UninstrumentedKotlin.WARNING -> optIn.add(UNINSTRUMENTED_WARNING_MARKER)
                    UninstrumentedKotlin.ERROR -> optIn.add(UNINSTRUMENTED_ERROR_MARKER)
                    UninstrumentedKotlin.OFF -> Unit
                }
            }
        }
        return project.providers.provider {
            listOf(
                SubpluginOption("selectionMode", extension.selection.mode.compilerValue),
                SubpluginOption(
                    "generatedTypesNamespace",
                    extension.generatedTypes.namespace,
                ),
                SubpluginOption("rawSuspendAbi", extension.jvm.rawSuspendAbi.compilerValue),
                SubpluginOption("sameNameCaller", extension.jvm.sameNameCaller.compilerValue),
                SubpluginOption(
                    "directImplementation",
                    extension.jvm.directImplementation.projection.compilerValue,
                ),
                SubpluginOption(
                    "directImplementationEnforcement",
                    extension.jvm.directImplementation.enforcement.compilerValue,
                ),
                SubpluginOption(
                    "uninstrumentedKotlin",
                    extension.jvm.directImplementation.uninstrumentedKotlin.compilerValue,
                ),
                SubpluginOption(
                    "blockingExportsEnabled",
                    extension.jvm.blocking.exports.enable.toString(),
                ),
                SubpluginOption(
                    "blockingEmitNamedCaller",
                    extension.jvm.blocking.exports.emitNamedCaller.toString(),
                ),
                SubpluginOption(
                    "blockingImportsEnabled",
                    extension.jvm.blocking.imports.enable.toString(),
                ),
                SubpluginOption(
                    "completionStageExportsEnabled",
                    extension.jvm.completionStage.exports.enable.toString(),
                ),
                SubpluginOption(
                    "completionStageEmitNamedCaller",
                    extension.jvm.completionStage.exports.emitNamedCaller.toString(),
                ),
                SubpluginOption(
                    "completionStageImportsEnabled",
                    extension.jvm.completionStage.imports.enable.toString(),
                ),
                SubpluginOption(
                    "completableFutureExportsEnabled",
                    extension.jvm.completableFuture.exports.enable.toString(),
                ),
                SubpluginOption(
                    "completableFutureEmitNamedCaller",
                    extension.jvm.completableFuture.exports.emitNamedCaller.toString(),
                ),
                SubpluginOption(
                    "completableFutureImportsEnabled",
                    extension.jvm.completableFuture.imports.enable.toString(),
                ),
                SubpluginOption("futureExportsEnabled", extension.jvm.future.exports.enable.toString()),
                SubpluginOption("futureEmitNamedCaller", extension.jvm.future.exports.emitNamedCaller.toString()),
                SubpluginOption("futureImportsEnabled", extension.jvm.future.imports.enable.toString()),
                SubpluginOption(
                    "emitCompatibilityGuard",
                    extension.jvm.runtimeGuards.compatibility.toString(),
                ),
                SubpluginOption(
                    "emitInvalidPathGuard",
                    extension.jvm.runtimeGuards.invalidPath.toString(),
                ),
            )
        }
    }

    private companion object {
        const val EXTENSION_NAME = "suspendProjection"
        const val VERIFY_TASK_NAME = "verifySuspendProjections"
        const val VERIFIER_CONFIGURATION = "suspendProjectionVerifier"
        const val COMPILER_PLUGIN_ID = "cn.chuanwise.kotlinsuspendprojection.compiler"
        const val GROUP = "cn.chuanwise.kotlinsuspendprojection"
        const val VERSION = "0.1.0-SNAPSHOT"
        const val KOTLIN_VERSION = "2.4.20"
        const val ANNOTATIONS_ARTIFACT = "annotations"
        const val RUNTIME_CORE_ARTIFACT = "runtime-core"
        const val RUNTIME_JVM_ARTIFACT = "runtime-jvm"
        const val COMPILER_ARTIFACT = "compiler-plugin"
        const val VERIFIER_ARTIFACT = "verification-jvm"
        const val UNINSTRUMENTED_WARNING_MARKER =
            "cn.chuanwise.kotlinsuspendprojection.annotations." +
                "UninstrumentedProjectionImplementationWarning"
        const val UNINSTRUMENTED_ERROR_MARKER =
            "cn.chuanwise.kotlinsuspendprojection.annotations." +
                "UninstrumentedProjectionImplementationError"
    }
}

public abstract class VerifySuspendProjectionsTask : JavaExec() {
    @get:Classpath
    public abstract val verifier: ConfigurableFileCollection

    @get:InputFiles
    public abstract val artifacts: ConfigurableFileCollection

    init {
        mainClass.set(
            "cn.chuanwise.kotlinsuspendprojection.verification.SuspendProjectionVerifierCli",
        )
    }

    @TaskAction
    override fun exec() {
        classpath = verifier
        setArgs(
            artifacts.files.sortedBy { it.absolutePath }.flatMap { artifact ->
                listOf("--artifact", artifact.absolutePath)
            },
        )
        super.exec()
    }
}
