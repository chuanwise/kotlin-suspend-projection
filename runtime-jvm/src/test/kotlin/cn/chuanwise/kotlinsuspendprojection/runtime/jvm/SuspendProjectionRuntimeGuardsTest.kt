package cn.chuanwise.kotlinsuspendprojection.runtime.jvm

import cn.chuanwise.kotlinsuspendprojection.runtime.IncompatibleSuspendProjectionRuntimeException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.FutureTask
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

    @Test
    fun futureExportsCompleteWithValuesAndFailures() {
        val success = startCompletableFutureSuspendProjection(
            1,
            "Foo.foo:completable-future:exports",
            true,
            true,
        ) { "ok" }
        assertEquals("ok", success.get())

        val failure = startCompletionStageSuspendProjection(
            1,
            "Foo.foo:completion-stage:exports",
            true,
            true,
        ) { throw ExampleFailure() }.toCompletableFuture()
        assertFailsWith<ExampleFailure> {
            try {
                failure.get()
            } catch (exception: java.util.concurrent.ExecutionException) {
                throw exception.cause ?: exception
            }
        }
    }

    @Test
    fun completionStageImportResumesWithoutTransportWrapper() {
        val stage = CompletableFuture<String>()
        Thread { stage.complete("async") }.start()
        assertEquals(
            "async",
            SuspendProjectionBlocking.await {
                awaitCompletionStageSuspendProjection(1, "stage", true, true) { stage }
            },
        )

        val failed = CompletableFuture<String>()
        failed.completeExceptionally(ExampleFailure())
        assertFailsWith<ExampleFailure> {
            SuspendProjectionBlocking.await {
                awaitCompletableFutureSuspendProjection(1, "future", true, true) { failed }
            }
        }
    }

    @Test
    fun plainFutureImportUsesGetAndUnwrapsExecutionException() {
        val success = FutureTask { "ok" }.also { it.run() }
        assertEquals(
            "ok",
            SuspendProjectionBlocking.await {
                awaitFutureSuspendProjection(1, "future-success", true, true) { success }
            },
        )

        val failure = FutureTask<String> { throw ExampleFailure() }.also { it.run() }
        assertFailsWith<ExampleFailure> {
            SuspendProjectionBlocking.await {
                awaitFutureSuspendProjection(1, "future-failure", true, true) { failure }
            }
        }
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
