package dev.suspendprojection.runtime.jvm

import dev.suspendprojection.runtime.IncompatibleSuspendProjectionRuntimeException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class SuspendProjectionRuntimeGuardsTest {
    @Test
    fun blockingAdapterReturnsSynchronousAndAsynchronousResults() {
        assertEquals("sync", SuspendProjectionBlocking.await { "sync" })
        assertEquals(
            "async",
            SuspendProjectionBlocking.await {
                suspendCoroutine { continuation ->
                    Thread { continuation.resume("async") }.start()
                }
            },
        )
    }

    @Test
    fun blockingAdapterPropagatesApplicationFailure() {
        assertFailsWith<ExampleFailure> {
            SuspendProjectionBlocking.await { throw ExampleFailure() }
        }
    }

    @Test
    fun compatibilityGuardUsesExplicitGeneratedCodeVersion() {
        assertFailsWith<IncompatibleSuspendProjectionRuntimeException> {
            SuspendProjectionRuntimeGuards.requireCompatible(2)
        }
    }

    @Test
    fun compatibilityGuardCanBeBypassedOperationally() {
        withProperty(SuspendProjectionRuntimeProperties.COMPATIBILITY_GUARD, "false") {
            SuspendProjectionRuntimeGuards.requireCompatible(2)
        }
    }

    @Test
    fun recursivePathIsRejectedUntilTokenCloses() {
        val token = SuspendProjectionRuntimeGuards.enterAdapterPath("Foo.foo:blocking:exports")
        try {
            assertFailsWith<InvalidSuspendProjectionPathException> {
                SuspendProjectionRuntimeGuards.enterAdapterPath("Foo.foo:blocking:exports")
            }
        } finally {
            token.close()
        }

        SuspendProjectionRuntimeGuards.enterAdapterPath("Foo.foo:blocking:exports").close()
    }

    @Test
    fun generatedCompatibilityGuardCanBeOmittedAtCompileTime() {
        assertEquals(
            "ok",
            awaitSuspendProjection(
                generatedCodeVersion = 2,
                pathId = "Foo.foo:blocking:exports",
                compatibilityGuardEnabled = false,
                invalidPathGuardEnabled = true,
            ) { "ok" },
        )
    }

    @Test
    fun generatedInvalidPathGuardCanBeOmittedAtCompileTime() {
        assertEquals(
            "ok",
            awaitSuspendProjection(
                generatedCodeVersion = 1,
                pathId = "Foo.foo:blocking:exports",
                compatibilityGuardEnabled = true,
                invalidPathGuardEnabled = true,
            ) {
                awaitSuspendProjection(
                    generatedCodeVersion = 1,
                    pathId = "Foo.foo:blocking:exports",
                    compatibilityGuardEnabled = true,
                    invalidPathGuardEnabled = false,
                ) { "ok" }
            },
        )
    }

    private inline fun withProperty(name: String, value: String, block: () -> Unit) {
        val previous = System.getProperty(name)
        try {
            System.setProperty(name, value)
            block()
        } finally {
            if (previous == null) {
                System.clearProperty(name)
            } else {
                System.setProperty(name, previous)
            }
        }
    }

    private class ExampleFailure : RuntimeException()
}
