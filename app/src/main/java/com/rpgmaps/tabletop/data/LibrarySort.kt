package com.rpgmaps.tabletop.data

import com.rpgmaps.tabletop.data.db.FolderEntity
import com.rpgmaps.tabletop.data.db.MapEntity

/**
 * How the library orders its maps and folders. Folders always come before
 * maps; this sets the order within each.
 */
enum class LibrarySort(val label: String) {
    /** A to Z, with numbers in number order: "Map 2" before "Map 10". */
    NAME("Name"),

    /** Most recently opened first; never-opened maps after, newest import first. */
    LAST_USED("Latest used"),

    /** Newest import first. */
    DATE_ADDED("Date added");

    fun sortMaps(maps: List<MapEntity>): List<MapEntity> = maps.sortedWith(
        when (this) {
            NAME -> compareBy<MapEntity, String>(NaturalOrder) { it.name }
            LAST_USED -> compareByDescending<MapEntity> { it.lastOpenedAt }.thenByDescending { it.createdAt }
            DATE_ADDED -> compareByDescending { it.createdAt }
        }.thenBy { it.id },
    )

    /**
     * Sorts [folders]. For [LAST_USED] a folder counts as used when any map
     * inside it, at any depth, was last opened -- so the campaign you are
     * playing stays on top. [allFolders] and [allMaps] are the whole library,
     * needed to see inside.
     */
    fun sortFolders(
        folders: List<FolderEntity>,
        allFolders: List<FolderEntity>,
        allMaps: List<MapEntity>,
    ): List<FolderEntity> {
        val comparator: Comparator<FolderEntity> = when (this) {
            NAME -> compareBy(NaturalOrder) { it.name }
            DATE_ADDED -> compareByDescending { it.createdAt }
            LAST_USED -> {
                val latest = latestUse(folders, allFolders, allMaps)
                compareByDescending<FolderEntity> { latest[it.id] ?: 0L }
                    .thenByDescending { it.createdAt }
            }
        }
        return folders.sortedWith(comparator.thenBy { it.id })
    }

    companion object {
        val DEFAULT = LAST_USED

        /** Unknown or missing values -- an older or newer app's setting -- fall back to [DEFAULT]. */
        fun fromKey(key: String?): LibrarySort = entries.firstOrNull { it.name == key } ?: DEFAULT

        private fun latestUse(
            folders: List<FolderEntity>,
            allFolders: List<FolderEntity>,
            allMaps: List<MapEntity>,
        ): Map<String, Long> {
            val wanted = folders.mapTo(HashSet()) { it.id }
            val out = HashMap<String, Long>()
            for (map in allMaps) {
                if (map.lastOpenedAt == 0L || map.folderId == null) continue
                // Credit every listed folder on the way up from the map.
                for (f in FolderTree.path(allFolders, map.folderId)) {
                    if (f.id in wanted && map.lastOpenedAt > (out[f.id] ?: 0L)) out[f.id] = map.lastOpenedAt
                }
            }
            return out
        }
    }
}

/**
 * Case-insensitive ordering that reads runs of digits as numbers, the way a
 * person sorts "Level 2" and "Level 10".
 */
object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                val endA = digitsEnd(a, i)
                val endB = digitsEnd(b, j)
                val numA = a.substring(i, endA).trimStart('0')
                val numB = b.substring(j, endB).trimStart('0')
                if (numA.length != numB.length) return numA.length - numB.length
                val byValue = numA.compareTo(numB)
                if (byValue != 0) return byValue
                i = endA
                j = endB
            } else {
                val byChar = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (byChar != 0) return byChar
                i++
                j++
            }
        }
        val byLength = (a.length - i) - (b.length - j)
        // Equal ignoring case and leading zeros: fall back to plain order so it is stable.
        return if (byLength != 0) byLength else a.compareTo(b)
    }

    private fun digitsEnd(s: String, from: Int): Int {
        var k = from
        while (k < s.length && s[k].isDigit()) k++
        return k
    }
}
