package cn.chuanwise.kotlinsuspendprojection.verification

import cn.chuanwise.kotlinsuspendprojection.annotations.SuspendProjectionMetadata
import cn.chuanwise.kotlinsuspendprojection.runtime.SuspendProjectionGeneratedCode
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

public data class SuspendProjectionVerificationIssue(
    public val className: String,
    public val message: String,
)

public data class SuspendProjectionVerificationResult(
    public val issues: List<SuspendProjectionVerificationIssue>,
) {
    public val isSuccessful: Boolean
        get() = issues.isEmpty()

    public fun requireSuccessful() {
        if (!isSuccessful) throw SuspendProjectionVerificationException(issues)
    }
}

public class SuspendProjectionVerificationException(
    public val issues: List<SuspendProjectionVerificationIssue>,
) : IllegalStateException(
    buildString {
        append("Suspend projection artifact verification failed:")
        issues.forEach { issue ->
            append("\n - ")
            append(issue.className.replace('/', '.'))
            append(": ")
            append(issue.message)
        }
    },
)

public object SuspendProjectionArtifactVerifier {
    @JvmStatic
    public fun verify(
        artifactRoots: Collection<Path>,
        classpathRoots: Collection<Path> = emptyList(),
    ): SuspendProjectionVerificationResult {
        val repository = ClassRepository()
        artifactRoots.forEach { repository.readRoot(it, subject = true) }
        classpathRoots.forEach { repository.readRoot(it, subject = false) }
        val issues = mutableListOf<SuspendProjectionVerificationIssue>()

        repository.subjectNames.map(repository.classes::getValue).forEach { classInfo ->
            classInfo.methods.values.forEach { method ->
                method.metadata?.let { metadata ->
                    if (metadata.schemaVersion != SuspendProjectionMetadata.SCHEMA_VERSION) {
                        issues += SuspendProjectionVerificationIssue(
                            classInfo.name,
                            "method ${method.displayName} uses metadata schema " +
                                "${metadata.schemaVersion}; expected " +
                                SuspendProjectionMetadata.SCHEMA_VERSION,
                        )
                    }
                    if (!SuspendProjectionGeneratedCode.isSupported(metadata.generatedCodeVersion)) {
                        issues += SuspendProjectionVerificationIssue(
                            classInfo.name,
                            "method ${method.displayName} uses generated-code version " +
                                "${metadata.generatedCodeVersion}; supported range is " +
                                "${SuspendProjectionGeneratedCode.MIN_SUPPORTED_VERSION}.." +
                                SuspendProjectionGeneratedCode.MAX_SUPPORTED_VERSION,
                        )
                    }
                }
            }
        }

        repository.subjectNames.forEach { subjectName ->
            val subject = repository.classes.getValue(subjectName)
            if (subject.isInterface || subject.isAbstract) return@forEach
            val contracts = linkedMapOf<MethodKey, String>()
            repository.collectStrictContracts(subject, contracts, mutableSetOf(), issues)
            contracts.forEach { (method, declaringInterface) ->
                when (repository.findConcreteMethod(subject, method, mutableSetOf())) {
                    MethodResolution.FOUND -> Unit
                    MethodResolution.MISSING -> issues += SuspendProjectionVerificationIssue(
                        subject.name,
                        "does not implement strict projection ${method.displayName} declared by " +
                            declaringInterface.replace('/', '.'),
                    )
                    MethodResolution.UNRESOLVED -> issues += SuspendProjectionVerificationIssue(
                        subject.name,
                        "cannot verify strict projection ${method.displayName} because a " +
                            "superclass is missing from the verification classpath",
                    )
                }
            }
        }

        return SuspendProjectionVerificationResult(issues.distinct())
    }
}

private const val META_DESCRIPTOR: String =
    "Lcn/chuanwise/kotlinsuspendprojection/annotations/SuspendProjectionMeta;"
private const val FLAG_DIRECT_IMPLEMENTATION: Int = 1 shl 1
private const val FLAG_ABSTRACT_CONTRACT: Int = 1 shl 2

private data class MethodKey(val name: String, val descriptor: String) {
    val displayName: String
        get() = name + descriptor
}

private data class GeneratedMetadata(
    var schemaVersion: Int = -1,
    var generatedCodeVersion: Int = -1,
    var flags: Int = 0,
)

private data class MethodInfo(
    val key: MethodKey,
    val access: Int,
    var metadata: GeneratedMetadata? = null,
) {
    val isAbstract: Boolean
        get() = access and Opcodes.ACC_ABSTRACT != 0
    val isStatic: Boolean
        get() = access and Opcodes.ACC_STATIC != 0
    val isStrictContract: Boolean
        get() = isAbstract && !isStatic && metadata?.flags?.let { flags ->
            flags and (FLAG_DIRECT_IMPLEMENTATION or FLAG_ABSTRACT_CONTRACT) ==
                (FLAG_DIRECT_IMPLEMENTATION or FLAG_ABSTRACT_CONTRACT)
        } == true
    val displayName: String
        get() = key.displayName
}

private data class ClassInfo(
    val name: String,
    val access: Int,
    val superName: String?,
    val interfaces: List<String>,
    val methods: MutableMap<MethodKey, MethodInfo> = linkedMapOf(),
) {
    val isInterface: Boolean
        get() = access and Opcodes.ACC_INTERFACE != 0
    val isAbstract: Boolean
        get() = access and Opcodes.ACC_ABSTRACT != 0
}

private enum class MethodResolution { FOUND, MISSING, UNRESOLVED }

private class ClassRepository {
    val classes: MutableMap<String, ClassInfo> = linkedMapOf()
    val subjectNames: MutableSet<String> = linkedSetOf()

    fun readRoot(root: Path, subject: Boolean) {
        require(Files.exists(root)) { "Verification root does not exist: ${root.toAbsolutePath()}" }
        if (Files.isDirectory(root)) {
            Files.walk(root).use { paths ->
                paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".class") }
                    .forEach { path -> Files.newInputStream(path).use { readClass(it, subject) } }
            }
        } else {
            JarFile(root.toFile()).use { jar ->
                val entries = jar.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (!entry.isDirectory && entry.name.endsWith(".class")) {
                        jar.getInputStream(entry).use { readClass(it, subject) }
                    }
                }
            }
        }
    }

    fun collectStrictContracts(
        owner: ClassInfo,
        destination: MutableMap<MethodKey, String>,
        visited: MutableSet<String>,
        issues: MutableList<SuspendProjectionVerificationIssue>,
    ) {
        if (!visited.add(owner.name)) return
        owner.interfaces.forEach { interfaceName ->
            val interfaceInfo = classes[interfaceName]
            if (interfaceInfo != null) {
                interfaceInfo.methods.values.filter(MethodInfo::isStrictContract).forEach {
                    destination.putIfAbsent(it.key, interfaceInfo.name)
                }
                collectStrictContracts(interfaceInfo, destination, visited, issues)
            }
        }
        owner.superName?.let { superName ->
            classes[superName]?.let {
                collectStrictContracts(it, destination, visited, issues)
            }
        }
    }

    fun findConcreteMethod(
        owner: ClassInfo,
        key: MethodKey,
        visited: MutableSet<String>,
    ): MethodResolution {
        if (!visited.add(owner.name)) return MethodResolution.MISSING
        owner.methods[key]?.let {
            if (!it.isAbstract && !it.isStatic) return MethodResolution.FOUND
        }
        val superName = owner.superName ?: return MethodResolution.MISSING
        if (superName == "java/lang/Object") return MethodResolution.MISSING
        val superInfo = classes[superName] ?: return MethodResolution.UNRESOLVED
        return findConcreteMethod(superInfo, key, visited)
    }

    private fun readClass(input: InputStream, subject: Boolean) {
        var result: ClassInfo? = null
        ClassReader(input).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visit(
                    version: Int,
                    access: Int,
                    name: String,
                    signature: String?,
                    superName: String?,
                    interfaces: Array<out String>,
                ) {
                    result = ClassInfo(name, access, superName, interfaces.toList())
                }

                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor {
                    val method = MethodInfo(MethodKey(name, descriptor), access)
                    result!!.methods[method.key] = method
                    return object : MethodVisitor(Opcodes.ASM9) {
                        override fun visitAnnotation(
                            descriptor: String,
                            visible: Boolean,
                        ): AnnotationVisitor? {
                            if (descriptor != META_DESCRIPTOR) return null
                            val metadata = GeneratedMetadata()
                            method.metadata = metadata
                            return object : AnnotationVisitor(Opcodes.ASM9) {
                                override fun visit(name: String, value: Any) {
                                    when (name) {
                                        "schemaVersion" -> metadata.schemaVersion = value as Int
                                        "generatedCodeVersion" ->
                                            metadata.generatedCodeVersion = value as Int
                                        "flags" -> metadata.flags = value as Int
                                    }
                                }
                            }
                        }
                    }
                }
            },
            ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
        )
        val classInfo = requireNotNull(result) { "Class file did not contain a class header" }
        if (subject || classInfo.name !in classes) classes[classInfo.name] = classInfo
        if (subject) subjectNames += classInfo.name
    }
}
