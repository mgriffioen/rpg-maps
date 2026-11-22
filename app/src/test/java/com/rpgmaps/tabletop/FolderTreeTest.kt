package com.rpgmaps.tabletop

import com.rpgmaps.tabletop.data.FolderTree
import com.rpgmaps.tabletop.data.db.FolderEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderTreeTest {

    private fun f(id: String, parent: String? = null, name: String = id) =
        FolderEntity(id = id, name = name, parentId = parent, createdAt = 0)

    private val campaign = f("campaign", name = "Campaign")
    private val ch1 = f("ch1", "campaign", "Chapter 1")
    private val ch2 = f("ch2", "campaign", "Chapter 2")
    private val crypt = f("crypt", "ch1", "Crypt")
    private val oneShots = f("oneshots", name = "One-shots")
    private val all = listOf(crypt, oneShots, ch2, campaign, ch1)

    @Test
    fun pathRunsFromTopLevelDown() {
        assertEquals(listOf(campaign, ch1, crypt), FolderTree.path(all, "crypt"))
        assertEquals(emptyList<FolderEntity>(), FolderTree.path(all, null))
        assertEquals(emptyList<FolderEntity>(), FolderTree.path(all, "gone"))
    }

    @Test
    fun isWithinIncludesTheFolderItself() {
        assertTrue(FolderTree.isWithin(all, "crypt", "campaign"))
        assertTrue(FolderTree.isWithin(all, "campaign", "campaign"))
        assertFalse(FolderTree.isWithin(all, "ch2", "ch1"))
        assertFalse(FolderTree.isWithin(all, null, "campaign"))
    }

    @Test
    fun flattenIsTreeOrderWithDepths() {
        val flat = FolderTree.flatten(all).map { it.folder.id to it.depth }
        assertEquals(
            listOf("campaign" to 0, "ch1" to 1, "crypt" to 2, "ch2" to 1, "oneshots" to 0),
            flat,
        )
    }

    @Test
    fun orphansAndCyclesStillAppear() {
        val orphan = f("orphan", "deleted")
        val a = f("a", "b")
        val b = f("b", "a")
        val flat = FolderTree.flatten(listOf(orphan, a, b)).map { it.folder.id }.toSet()
        assertEquals(setOf("orphan", "a", "b"), flat)
        // And walking up a cycle terminates.
        FolderTree.path(listOf(a, b), "a")
    }
}
