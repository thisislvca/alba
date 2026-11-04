package me.lvca.alba.protocol.status

import me.lvca.alba.engine.model.ProtocolArea
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** The status document's format, its version ranges, and the copy committed to the repository. */
class ProtocolStatusDocumentTest {
    @Test
    fun readsPausesWithAreasVersionsAndMessages() {
        val entries = ProtocolStatusDocument.parse(
            """
            {
              "schemaVersion": 1,
              "pauses": [
                {
                  "id": "upload-change",
                  "areas": ["uploads", "library-edits"],
                  "versions": { "min": "0.2.0", "max": "0.4.2" },
                  "message": { "en": "Backup is paused.", "it": "Il backup è in pausa." },
                  "note": "fields this version does not know are ignored"
                },
                { "id": "everything", "areas": ["all"], "message": "Paused." }
              ]
            }
            """,
        )

        val first = entries[0]
        assertEquals("upload-change", first.id)
        assertEquals(setOf(ProtocolArea.UPLOADS, ProtocolArea.LIBRARY_EDITS), first.areas)
        assertEquals(AppVersion(0, 2, 0), first.minVersion)
        assertEquals(AppVersion(0, 4, 2), first.maxVersion)
        assertEquals("Il backup è in pausa.", first.toPause().message("it"))
        assertEquals("Backup is paused.", first.toPause().message("fr"))
        assertEquals(ProtocolArea.entries.toSet(), entries[1].areas)
        assertEquals(mapOf("en" to "Paused."), entries[1].messages)
    }

    @Test
    fun anEmptyDocumentPausesNothing() {
        assertTrue(ProtocolStatusDocument.parse("""{ "schemaVersion": 1, "pauses": [] }""").isEmpty())
        assertTrue(ProtocolStatusDocument.parse("""{ "schemaVersion": 1 }""").isEmpty())
    }

    @Test
    fun aNewerDocumentIsReadForWhatThisVersionKnows() {
        val entries = ProtocolStatusDocument.parse(
            """{ "schemaVersion": 2, "pauses": [ { "id": "a", "areas": ["uploads", "a-future-area"] }, { "id": "b", "areas": ["a-future-area"] } ] }""",
        )

        assertEquals(setOf(ProtocolArea.UPLOADS), entries[0].areas)
        assertTrue("An area this version cannot name pauses nothing", entries[1].areas.isEmpty())
        assertEquals(listOf("a"), effectivePauses(entries, AppVersion(1, 0, 0)).map { it.id })
    }

    @Test
    fun aMalformedDocumentIsRefusedAsAWhole() {
        listOf(
            "not json",
            "[]",
            """{ "pauses": [] }""",
            """{ "schemaVersion": 0, "pauses": [] }""",
            """{ "schemaVersion": 1, "pauses": {} }""",
            """{ "schemaVersion": 1, "pauses": [ { "areas": ["uploads"] } ] }""",
            """{ "schemaVersion": 1, "pauses": [ { "id": "a", "areas": [] } ] }""",
            """{ "schemaVersion": 1, "pauses": [ { "id": "a", "areas": "uploads" } ] }""",
            """{ "schemaVersion": 1, "pauses": [ { "id": "a", "areas": ["uploads"], "versions": { "min": "1.0" } } ] }""",
            """{ "schemaVersion": 1, "pauses": [ { "id": "a", "areas": ["uploads"], "versions": { "min": "2.0.0", "max": "1.0.0" } } ] }""",
            """{ "schemaVersion": 1, "pauses": [ { "id": "a", "areas": ["uploads"], "message": 3 } ] }""",
            """{ "schemaVersion": 1, "pauses": [ { "id": "a", "areas": ["uploads"] }, { "id": "a", "areas": ["uploads"] } ] }""",
        ).forEach { document ->
            try {
                ProtocolStatusDocument.parse(document)
                fail("Accepted a malformed document: $document")
            } catch (_: IllegalArgumentException) {
                // Expected: the app keeps its last good status instead.
            }
        }
    }

    @Test
    fun versionRangesAreInclusiveAndCompareNumerically() {
        val range = PauseEntry("a", setOf(ProtocolArea.UPLOADS), AppVersion(0, 9, 0), AppVersion(0, 10, 2))

        assertFalse(range.appliesTo(AppVersion(0, 8, 9)))
        assertTrue(range.appliesTo(AppVersion(0, 9, 0)))
        assertTrue("0.10 is after 0.9, not before it", range.appliesTo(AppVersion(0, 10, 0)))
        assertTrue(range.appliesTo(AppVersion(0, 10, 2)))
        assertFalse(range.appliesTo(AppVersion(0, 10, 3)))
        assertFalse(range.appliesTo(AppVersion(1, 0, 0)))

        val from = PauseEntry("b", setOf(ProtocolArea.UPLOADS), minVersion = AppVersion(1, 2, 0))
        assertFalse(from.appliesTo(AppVersion(1, 1, 9)))
        assertTrue(from.appliesTo(AppVersion(9, 0, 0)))
        val upTo = PauseEntry("c", setOf(ProtocolArea.UPLOADS), maxVersion = AppVersion(1, 2, 0))
        assertTrue(upTo.appliesTo(AppVersion(0, 0, 1)))
        assertFalse(upTo.appliesTo(AppVersion(1, 2, 1)))
    }

    @Test
    fun anUnreadableAppVersionIsPausedOnlyByPausesForEveryVersion() {
        val everyVersion = PauseEntry("a", setOf(ProtocolArea.UPLOADS))
        val ranged = PauseEntry("b", setOf(ProtocolArea.UPLOADS), maxVersion = AppVersion(9, 9, 9))

        assertTrue(everyVersion.appliesTo(null))
        assertFalse(ranged.appliesTo(null))
    }

    @Test
    fun appVersionsReadTheLeadingMajorMinorPatch() {
        assertEquals(AppVersion(0, 4, 2), AppVersion.parse("0.4.2"))
        assertEquals(AppVersion(0, 4, 2, "dev"), AppVersion.parse("0.4.2-dev"))
        assertEquals(AppVersion(0, 5, 0, "rc.1"), AppVersion.parse("0.5.0-rc.1"))
        assertEquals(AppVersion(12, 0, 31), AppVersion.parse("12.0.31"))
        listOf("dev", "1.2", "01.2.3", "0.4.2.1", "0.4.2x", "0.4.2-", "0.4.2-rc..1", "0.4.2 beta").forEach {
            assertNull("Parsed $it", AppVersion.parse(it))
        }
    }

    @Test
    fun preReleasesComeBeforeTheirRelease() {
        fun v(name: String) = requireNotNull(AppVersion.parse(name))

        assertTrue(v("0.5.0-rc1") < v("0.5.0"))
        assertTrue(v("0.5.0-dev") < v("0.5.0"))
        assertTrue(v("0.5.0-rc1") > v("0.4.9"))
        assertTrue(v("0.5.0-rc1") < v("0.5.0-rc2"))
        assertTrue("Numbers compare numerically", v("0.5.0-rc.2") < v("0.5.0-rc.10"))
        assertTrue("Numbers come before words", v("0.5.0-1") < v("0.5.0-alpha"))
        assertTrue("A longer pre-release with the same start is later", v("0.5.0-rc") < v("0.5.0-rc.1"))
        assertEquals(0, v("0.5.0-rc1").compareTo(v("0.5.0-rc1")))

        val upTo = PauseEntry("a", setOf(ProtocolArea.UPLOADS), maxVersion = v("0.5.0"))
        assertTrue("max 0.5.0 covers its release candidates", upTo.appliesTo(v("0.5.0-rc1")))
        val from = PauseEntry("b", setOf(ProtocolArea.UPLOADS), minVersion = v("0.5.0"))
        assertFalse("min 0.5.0 leaves its release candidates out", from.appliesTo(v("0.5.0-rc1")))
    }

    @Test
    fun theCommittedStatusDocumentIsValidAndNamesOnlyKnownAreas() {
        val file = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .map { File(it, "status/protocol-status.json") }
            .firstOrNull { it.isFile }
            ?: error("status/protocol-status.json is missing from the repository")
        val document = file.readText()
        val entries = ProtocolStatusDocument.parse(document)

        // Every area the committed file names must be one this version knows, so a typo cannot silently pause nothing.
        val named = Regex(""""areas"\s*:\s*\[([^\]]*)]""").findAll(document)
            .flatMap { Regex(""""([^"]+)"""").findAll(it.groupValues[1]).map { area -> area.groupValues[1] } }
            .toSet()
        val known = ProtocolArea.entries.map { it.key }.toSet() + ProtocolArea.ALL
        assertTrue("Unknown areas in status/protocol-status.json: ${named - known}", known.containsAll(named))
        assertTrue(entries.all { it.areas.isNotEmpty() })
    }
}
