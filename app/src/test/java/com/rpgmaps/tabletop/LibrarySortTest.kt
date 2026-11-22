package com.rpgmaps.tabletop

import com.rpgmaps.tabletop.data.LibrarySort
import com.rpgmaps.tabletop.data.NaturalOrder
import com.rpgmaps.tabletop.data.db.FolderEntity
import com.rpgmaps.tabletop.data.db.MapEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibrarySortTest {

    private fun map(id: String, name: String = id, created: Long = 0, opened: Long = 0, folder: String? = null) =
        MapEntity(
            id = id, name = name, imageW = 1, imageH = 1, fogW = 1, fogH = 1,
            folderId = folder, createdAt = created, lastOpenedAt = opened,
        )

    private fun folder(id: String, name: String = id, parent: String? = null, created: Long = 0) =
        FolderEntity(id = id, name = name, parentId = parent, createdAt = created)

    @Test
    fun nameSortIsCaseInsensitiveAndReadsNumbers() {
        val maps = listOf(
            map("a", "Level 10"), map("b", "level 2"), map("c", "Crypt"), map("d", "Level 1"),
        )
        assertEquals(
            listOf("Crypt", "Level 1", "level 2", "Level 10"),
            LibrarySort.NAME.sortMaps(maps).map { it.name },
        )
    }

    @Test
    fun naturalOrderBasics() {
        assertTrue(NaturalOrder.compare("Map 2", "Map 10") < 0)
        assertTrue(NaturalOrder.compare("map", "Map 1") < 0)
        assertTrue(NaturalOrder.compare("B", "a") > 0)
        assertTrue(NaturalOrder.compare("Room 007", "Room 8") < 0)
    }

    @Test
    fun latestUsedPutsNeverOpenedLastNewestFirst() {
        val maps = listOf(
            map("old-unopened", created = 1),
            map("opened-early", created = 2, opened = 100),
            map("new-unopened", created = 5),
            map("opened-late", created = 3, opened = 200),
        )
        assertEquals(
            listOf("opened-late", "opened-early", "new-unopened", "old-unopened"),
            LibrarySort.LAST_USED.sortMaps(maps).map { it.id },
        )
    }

    @Test
    fun dateAddedIsNewestFirst() {
        val maps = listOf(map("a", created = 1, opened = 999), map("b", created = 3), map("c", created = 2))
        assertEquals(listOf("b", "c", "a"), LibrarySort.DATE_ADDED.sortMaps(maps).map { it.id })
    }

    @Test
    fun foldersByLatestUseCountNestedMaps() {
        val campaign = folder("campaign", created = 1)
        val chapter = folder("chapter", parent = "campaign", created = 2)
        val oneShots = folder("oneshots", created = 3)
        val empty = folder("empty", created = 4)
        val all = listOf(campaign, chapter, oneShots, empty)
        val maps = listOf(
            map("deep", folder = "chapter", opened = 500),
            map("shot", folder = "oneshots", opened = 300),
        )
        val top = listOf(empty, oneShots, campaign)
        assertEquals(
            listOf("campaign", "oneshots", "empty"),
            LibrarySort.LAST_USED.sortFolders(top, all, maps).map { it.id },
        )
        assertEquals(
            listOf("empty", "oneshots", "campaign"),
            LibrarySort.DATE_ADDED.sortFolders(top, all, maps).map { it.id },
        )
    }

    @Test
    fun unknownStoredValueFallsBackToDefault() {
        assertEquals(LibrarySort.DEFAULT, LibrarySort.fromKey(null))
        assertEquals(LibrarySort.DEFAULT, LibrarySort.fromKey("SIZE"))
        assertEquals(LibrarySort.NAME, LibrarySort.fromKey("NAME"))
    }
}
