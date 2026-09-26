package cn.chuanwise.kotlinsuspendprojection.verification

import java.nio.file.Path
import java.nio.file.Paths

public object SuspendProjectionVerifierCli {
    @JvmStatic
    public fun main(args: Array<String>) {
        val artifacts = mutableListOf<Path>()
        val classpath = mutableListOf<Path>()
        var index = 0
        while (index < args.size) {
            val destination = when (args[index]) {
                "--artifact" -> artifacts
                "--classpath" -> classpath
                else -> error("Unknown argument: ${args[index]}")
            }
            index++
            require(index < args.size) { "Missing path after ${args[index - 1]}" }
            destination.add(Paths.get(args[index]))
            index++
        }
        require(artifacts.isNotEmpty()) { "At least one --artifact path is required" }

        SuspendProjectionArtifactVerifier.verify(artifacts, classpath).requireSuccessful()
        println("Suspend projection artifact verification passed for ${artifacts.size} root(s)")
    }
}
