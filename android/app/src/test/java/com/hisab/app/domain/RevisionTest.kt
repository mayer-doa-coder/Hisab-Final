package com.hisab.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class RevisionTest {
    private val t1 = Instant.parse("2026-01-01T00:00:00Z")
    private val t2 = Instant.parse("2026-01-02T00:00:00Z")
    private val t2b = Instant.parse("2026-01-02T01:00:00Z")
    private val t3 = Instant.parse("2026-01-03T00:00:00Z")

    @Test
    fun `checkRevision accepts a write against the current revision`() {
        assertEquals(RevisionCheckResult.Ok, checkRevision(1, 1))
    }

    @Test
    fun `checkRevision rejects a write against a stale revision`() {
        assertEquals(RevisionCheckResult.Conflict, checkRevision(2, 1))
    }

    @Test
    fun `createRevisioned starts at revision 1 with no tombstone`() {
        val created = createRevisioned("Coke 500ml", t1)
        assertEquals(1, created.revision)
        assertNull(created.deletedAt)
    }

    @Test
    fun `applyRevisionedUpdate succeeds and bumps the revision when base_revision matches`() {
        val created = createRevisioned("Coke 500ml", t1)
        val write = applyRevisionedUpdate(created, baseRevision = 1, newData = "Coca-Cola 500ml", updatedAt = t2)

        assertEquals(RevisionCheckResult.Ok, write.result)
        assertEquals(2, write.next.revision)
        assertEquals("Coca-Cola 500ml", write.next.data)
    }

    // Same scenario as server/src/domain/revision.test.ts and
    // docs/PHASE_GUIDE.md Step 34 (M1): two devices edit the same product
    // offline, then both sync. The second one to land must be rejected, not
    // silently overwrite the first.
    @Test
    fun `two offline edits to the same entity - the second to arrive is rejected, not silently applied`() {
        val created = createRevisioned("Coke 500ml", t1)

        // Phone A edits first, from revision 1. Succeeds; server is now at revision 2.
        val phoneA = applyRevisionedUpdate(created, baseRevision = 1, newData = "Coca-Cola 500ml", updatedAt = t2)
        assertEquals(RevisionCheckResult.Ok, phoneA.result)
        val serverState = phoneA.next

        // Phone B was also offline since revision 1, and now tries to sync its
        // own edit — still against base_revision 1, which is now stale.
        val phoneB =
            applyRevisionedUpdate(serverState, baseRevision = 1, newData = "Coca Cola (500 ML)", updatedAt = t2b)

        assertEquals(RevisionCheckResult.Conflict, phoneB.result)
        // Rejected means untouched: still Phone A's name and revision, not Phone B's.
        assertEquals("Coca-Cola 500ml", phoneB.next.data)
        assertEquals(2, phoneB.next.revision)
    }

    @Test
    fun `applyRevisionedDelete sets a tombstone instead of being silently dropped`() {
        val created = createRevisioned("Coke 500ml", t1)
        val write = applyRevisionedDelete(created, baseRevision = 1, deletedAt = t3)

        assertEquals(RevisionCheckResult.Ok, write.result)
        assertEquals(t3, write.next.deletedAt)
        assertEquals(2, write.next.revision)
        // The underlying data is preserved, not erased — it's a tombstone, not a delete.
        assertEquals("Coke 500ml", write.next.data)
    }

    @Test
    fun `a delete against a stale revision is rejected, same as an update`() {
        val created = createRevisioned("Coke 500ml", t1)
        val write = applyRevisionedDelete(created, baseRevision = 0, deletedAt = t3)

        assertEquals(RevisionCheckResult.Conflict, write.result)
        assertNull(write.next.deletedAt)
    }

    @Test
    fun `Conflict carries the language-neutral REVISION_CONFLICT code`() {
        assertTrue(RevisionCheckResult.Conflict.CODE == "REVISION_CONFLICT")
        assertEquals(REVISION_CONFLICT, RevisionCheckResult.Conflict.CODE)
    }
}
