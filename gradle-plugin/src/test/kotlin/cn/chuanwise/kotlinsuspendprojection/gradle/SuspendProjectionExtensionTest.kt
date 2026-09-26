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
            enabled = true
            selection { mode = SelectionMode.ANNOTATED }
            generatedTypes { namespace = "Interop" }
            primary(JvmProjection.BLOCKING)
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
    fun `default configuration keeps named caller and explicit implementation interface`() {
        val extension = SuspendProjectionExtension()

        extension.validate()

        assertTrue(extension.enabled)
        assertEquals(SelectionMode.ANNOTATED, extension.selection.mode)
        assertEquals("Projections", extension.generatedTypes.namespace)
        assertEquals(JvmProjection.NONE, extension.jvm.sameNameCaller)
        assertEquals(JvmProjection.NONE, extension.jvm.directImplementation.projection)
        assertTrue(extension.jvm.blocking.exports.emitNamedCaller)
        assertTrue(extension.jvm.blocking.imports.enabled)
    }

    @Test
    fun `unsupported projection choice fails instead of being ignored`() {
        val extension = SuspendProjectionExtension().apply {
            jvm { sameNameCaller = JvmProjection.COMPLETION_STAGE }
        }

        assertFailsWith<IllegalArgumentException> { extension.validate() }
    }
}
