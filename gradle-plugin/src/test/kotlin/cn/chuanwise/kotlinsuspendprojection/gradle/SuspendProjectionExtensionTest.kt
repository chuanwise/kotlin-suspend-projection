package cn.chuanwise.kotlinsuspendprojection.gradle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SuspendProjectionExtensionTest {
    @Test
    fun `blocking primary preset configures caller and implementation policies`() {
        val extension = SuspendProjectionExtension().apply {
            enable = true
            selection { mode = SelectionMode.ANNOTATED }
            generatedTypes { namespace = "Interop" }
            primary = JvmProjection.BLOCKING
            jvm {
                runtimeGuards {
                    compatibility = false
                    invalidPath = false
                }
            }
        }

        extension.validate()

        assertEquals(SelectionMode.ANNOTATED, extension.selection.mode)
        assertEquals("Interop", extension.generatedTypes.namespace)
        assertEquals(JvmProjection.BLOCKING, extension.jvm.sameNameCaller)
        assertEquals(JvmProjection.BLOCKING, extension.jvm.directImplementation.projection)
        assertFalse(extension.jvm.blocking.exports.emitNamedCaller)
        assertEquals(
            DirectImplementationEnforcement.STRICT,
            extension.jvm.directImplementation.enforcement,
        )
        assertEquals(
            UninstrumentedKotlin.ERROR,
            extension.jvm.directImplementation.uninstrumentedKotlin,
        )
        assertFalse(extension.jvm.runtimeGuards.compatibility)
        assertFalse(extension.jvm.runtimeGuards.invalidPath)
    }

    @Test
    fun `default configuration uses only blocking primary`() {
        val extension = SuspendProjectionExtension()

        extension.validate()

        assertTrue(extension.enable)
        assertEquals(SelectionMode.ANNOTATED, extension.selection.mode)
        assertEquals("Projections", extension.generatedTypes.namespace)
        assertEquals(setOf(JvmProjection.BLOCKING), extension.projections)
        assertEquals(JvmProjection.BLOCKING, extension.primary)
        assertEquals(JvmProjection.BLOCKING, extension.jvm.sameNameCaller)
        assertEquals(JvmProjection.BLOCKING, extension.jvm.directImplementation.projection)
        assertFalse(extension.jvm.blocking.exports.emitNamedCaller)
        assertTrue(extension.jvm.blocking.imports.enable)
        assertFalse(extension.jvm.completionStage.exports.enable)
        assertTrue(extension.jvm.completionStage.exports.emitNamedCaller)
        assertFalse(extension.jvm.completionStage.imports.enable)
        assertFalse(extension.jvm.completableFuture.exports.enable)
        assertTrue(extension.jvm.completableFuture.exports.emitNamedCaller)
        assertFalse(extension.jvm.completableFuture.imports.enable)
        assertFalse(extension.jvm.future.exports.enable)
        assertTrue(extension.jvm.future.exports.emitNamedCaller)
        assertFalse(extension.jvm.future.imports.enable)
    }

    @Test
    fun `completion stage primary configures strict direct implementation`() {
        val extension = SuspendProjectionExtension().apply {
            projections = setOf(JvmProjection.COMPLETION_STAGE)
            primary = JvmProjection.COMPLETION_STAGE
        }

        extension.validate()

        assertEquals(JvmProjection.COMPLETION_STAGE, extension.jvm.sameNameCaller)
        assertEquals(
            JvmProjection.COMPLETION_STAGE,
            extension.jvm.directImplementation.projection,
        )
        assertFalse(extension.jvm.completionStage.exports.emitNamedCaller)
        assertEquals(
            DirectImplementationEnforcement.STRICT,
            extension.jvm.directImplementation.enforcement,
        )
        assertEquals(
            UninstrumentedKotlin.ERROR,
            extension.jvm.directImplementation.uninstrumentedKotlin,
        )
    }

    @Test
    fun `continuation policy choice fails instead of being ignored`() {
        val extension = SuspendProjectionExtension().apply {
            jvm { sameNameCaller = JvmProjection.CONTINUATION }
        }

        assertFailsWith<IllegalArgumentException> { extension.validate() }
    }
}
