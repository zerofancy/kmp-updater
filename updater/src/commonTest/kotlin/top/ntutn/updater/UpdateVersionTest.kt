package top.ntutn.updater

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdateVersionTest {
    @Test
    fun comparesCodesNumericallyRatherThanDisplayNames() {
        assertTrue(UpdateVersion(10, "old label").isNewerThan(UpdateVersion(9, "999.0")))
        assertFalse(UpdateVersion(9, "999.0").isNewerThan(UpdateVersion(10, "1.0")))
    }

    @Test
    fun equalCodesDoNotOfferAnUpdate() {
        assertFalse(UpdateVersion(2, "new label").isNewerThan(UpdateVersion(2, "old label")))
    }

    @Test
    fun supportsFullLongRange() {
        assertTrue(UpdateVersion(Long.MAX_VALUE, "latest").isNewerThan(UpdateVersion(1, "first")))
    }

    @Test
    fun rejectsInvalidVersions() {
        assertFailsWith<IllegalArgumentException> { UpdateVersion(0, "1.0") }
        assertFailsWith<IllegalArgumentException> { UpdateVersion(-1, "1.0") }
        assertFailsWith<IllegalArgumentException> { UpdateVersion(1, "  ") }
    }
}
