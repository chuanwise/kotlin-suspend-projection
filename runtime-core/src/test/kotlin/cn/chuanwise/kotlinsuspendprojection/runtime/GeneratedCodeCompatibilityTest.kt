package cn.chuanwise.kotlinsuspendprojection.runtime

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GeneratedCodeCompatibilityTest {
    @Test
    fun supportedRangeIsInclusive() {
        assertTrue(SuspendProjectionGeneratedCode.isSupported(1))
        assertFalse(SuspendProjectionGeneratedCode.isSupported(0))
        assertFalse(SuspendProjectionGeneratedCode.isSupported(2))
    }

    @Test
    fun unsupportedVersionReportsBothSides() {
        val error = assertFailsWith<IncompatibleSuspendProjectionRuntimeException> {
            SuspendProjectionGeneratedCode.requireSupported(2)
        }

        assertTrue(error.message.orEmpty().contains("2"))
        assertTrue(error.message.orEmpty().contains("1..1"))
    }
}
