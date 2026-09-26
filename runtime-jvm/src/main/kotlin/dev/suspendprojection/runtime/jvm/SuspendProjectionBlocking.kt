package dev.suspendprojection.runtime.jvm

import dev.suspendprojection.runtime.SuspendProjectionCancellationCapabilities
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/** Minimal blocking export support without a kotlinx.coroutines dependency. */
public object SuspendProjectionBlocking {
    public const val CANCELLATION_CAPABILITY: String =
        SuspendProjectionCancellationCapabilities.WAITER_INTERRUPTION_ONLY

    @JvmStatic
    @Throws(InterruptedException::class)
    public fun <T> await(block: suspend () -> T): T {
        val completion = CountDownLatch(1)
        val outcome = AtomicReference<Result<T>?>(null)

        block.startCoroutine(
            object : Continuation<T> {
                override val context = EmptyCoroutineContext

                override fun resumeWith(result: Result<T>) {
                    outcome.set(result)
                    completion.countDown()
                }
            },
        )

        completion.await()
        return checkNotNull(outcome.get()) {
            "Suspend projection completed without publishing an outcome"
        }.getOrThrow()
    }
}

/** Compiler-facing entry point used by generated blocking exports. */
@JvmSynthetic
@Throws(InterruptedException::class)
public fun <T> awaitSuspendProjection(
    generatedCodeVersion: Int,
    pathId: String,
    compatibilityGuardEnabled: Boolean,
    invalidPathGuardEnabled: Boolean,
    block: suspend () -> T,
): T {
    if (compatibilityGuardEnabled) {
        SuspendProjectionRuntimeGuards.requireCompatible(generatedCodeVersion)
    }
    val pathToken = if (invalidPathGuardEnabled) {
        SuspendProjectionRuntimeGuards.enterAdapterPath(pathId)
    } else {
        null
    }
    try {
        return SuspendProjectionBlocking.await(block)
    } finally {
        pathToken?.close()
    }
}
