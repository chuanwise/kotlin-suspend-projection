package cn.chuanwise.kotlinsuspendprojection.runtime.jvm

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.startCoroutine
import kotlin.coroutines.suspendCoroutine

@JvmSynthetic
public fun <T> startCompletionStageSuspendProjection(
    generatedCodeVersion: Int,
    pathId: String,
    compatibilityGuardEnabled: Boolean,
    invalidPathGuardEnabled: Boolean,
    block: suspend () -> T,
): CompletionStage<T> = startCompletableFutureProjection(
    generatedCodeVersion,
    pathId,
    compatibilityGuardEnabled,
    invalidPathGuardEnabled,
    block,
)

@JvmSynthetic
public fun <T> startCompletableFutureSuspendProjection(
    generatedCodeVersion: Int,
    pathId: String,
    compatibilityGuardEnabled: Boolean,
    invalidPathGuardEnabled: Boolean,
    block: suspend () -> T,
): CompletableFuture<T> = startCompletableFutureProjection(
    generatedCodeVersion,
    pathId,
    compatibilityGuardEnabled,
    invalidPathGuardEnabled,
    block,
)

@JvmSynthetic
public fun <T> startFutureSuspendProjection(
    generatedCodeVersion: Int,
    pathId: String,
    compatibilityGuardEnabled: Boolean,
    invalidPathGuardEnabled: Boolean,
    block: suspend () -> T,
): Future<T> = startCompletableFutureProjection(
    generatedCodeVersion,
    pathId,
    compatibilityGuardEnabled,
    invalidPathGuardEnabled,
    block,
)

@JvmSynthetic
public suspend fun <T> awaitCompletionStageSuspendProjection(
    generatedCodeVersion: Int,
    pathId: String,
    compatibilityGuardEnabled: Boolean,
    invalidPathGuardEnabled: Boolean,
    provider: () -> CompletionStage<T>,
): T {
    val stage = guardedValue(
        generatedCodeVersion,
        pathId,
        compatibilityGuardEnabled,
        invalidPathGuardEnabled,
        provider,
    )
    return suspendCoroutine { continuation ->
        stage.whenComplete { value, error ->
            if (error == null) continuation.resume(value)
            else continuation.resumeWithException(unwrapTransportException(error))
        }
    }
}

@JvmSynthetic
public suspend fun <T> awaitCompletableFutureSuspendProjection(
    generatedCodeVersion: Int,
    pathId: String,
    compatibilityGuardEnabled: Boolean,
    invalidPathGuardEnabled: Boolean,
    provider: () -> CompletableFuture<T>,
): T = awaitCompletionStageSuspendProjection(
    generatedCodeVersion,
    pathId,
    compatibilityGuardEnabled,
    invalidPathGuardEnabled,
    provider,
)

@JvmSynthetic
@Throws(InterruptedException::class)
public suspend fun <T> awaitFutureSuspendProjection(
    generatedCodeVersion: Int,
    pathId: String,
    compatibilityGuardEnabled: Boolean,
    invalidPathGuardEnabled: Boolean,
    provider: () -> Future<T>,
): T {
    val future = guardedValue(
        generatedCodeVersion,
        pathId,
        compatibilityGuardEnabled,
        invalidPathGuardEnabled,
        provider,
    )
    return try {
        future.get()
    } catch (exception: ExecutionException) {
        throw exception.cause ?: exception
    }
}

private fun <T> startCompletableFutureProjection(
    generatedCodeVersion: Int,
    pathId: String,
    compatibilityGuardEnabled: Boolean,
    invalidPathGuardEnabled: Boolean,
    block: suspend () -> T,
): CompletableFuture<T> {
    val future = CompletableFuture<T>()
    guardedValue(
        generatedCodeVersion,
        pathId,
        compatibilityGuardEnabled,
        invalidPathGuardEnabled,
    ) {
        block.startCoroutine(
            object : Continuation<T> {
                override val context = EmptyCoroutineContext

                override fun resumeWith(result: Result<T>) {
                    result.fold(future::complete, future::completeExceptionally)
                }
            },
        )
    }
    return future
}

private inline fun <T> guardedValue(
    generatedCodeVersion: Int,
    pathId: String,
    compatibilityGuardEnabled: Boolean,
    invalidPathGuardEnabled: Boolean,
    block: () -> T,
): T {
    if (compatibilityGuardEnabled) {
        SuspendProjectionRuntimeGuards.requireCompatible(generatedCodeVersion)
    }
    val token = if (invalidPathGuardEnabled) {
        SuspendProjectionRuntimeGuards.enterAdapterPath(pathId)
    } else {
        null
    }
    try {
        return block()
    } finally {
        token?.close()
    }
}

private fun unwrapTransportException(error: Throwable): Throwable = when (error) {
    is CompletionException -> error.cause?.let(::unwrapTransportException) ?: error
    is ExecutionException -> error.cause?.let(::unwrapTransportException) ?: error
    else -> error
}
