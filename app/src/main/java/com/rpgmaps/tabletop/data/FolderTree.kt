package com.rpgmaps.tabletop.data

import com.rpgmaps.tabletop.data.db.FolderEntity

/**
 * Pure helpers over the flat folder list, kept free of Android so they can be
 * unit tested. Every walk up the tree is bounded by the number of folders, so
 * a cycle that somehow got into the database cannot hang the library.
 */
object FolderTree {

    /** The folders from the top level down to [folderId], inclusive. Empty for the top level. */
    fun path(folders: List<FolderEntity>, folderId: String?): List<FolderEntity> {
        val byId = folders.associateBy { it.id }
        val path = ArrayList<FolderEntity>()
        var current = folderId?.let(byId::get)
        while (current != null && path.size <= folders.size) {
            path += current
            current = current.parentId?.let(byId::get)
        }
        return path.reversed()
    }

    /** True when [folderId] is [ancestorId] or sits somewhere inside it. */
    fun isWithin(folders: List<FolderEntity>, folderId: String?, ancestorId: String): Boolean =
        path(folders, folderId).any { it.id == ancestorId }

    /** One row of [flatten]: a folder and how deep it sits (0 = top level). */
    data class Entry(val folder: FolderEntity, val depth: Int)

    /**
     * Every folder in tree order -- each followed by its contents, siblings
     * sorted by name -- for a "move to" list. Folders whose parent no longer
     * exists are treated as top level so they can never disappear from view.
     */
    fun flatten(folders: List<FolderEntity>): List<Entry> {
        val ids = folders.mapTo(HashSet()) { it.id }
        val children = folders.groupBy { f -> f.parentId?.takeIf { it in ids && it != f.id } }
            .mapValues { (_, list) -> list.sortedBy { it.name.lowercase() } }
        val out = ArrayList<Entry>(folders.size)
        val seen = HashSet<String>()
        fun walk(parentId: String?, depth: Int) {
            for (f in children[parentId].orEmpty()) {
                if (!seen.add(f.id)) continue
                out += Entry(f, depth)
                walk(f.id, depth + 1)
            }
        }
        walk(null, 0)
        // Anything left over is part of a cycle; show it at the top level.
        for (f in folders) if (f.id !in seen) {
            seen += f.id
            out += Entry(f, 0)
        }
        return out
    }
}
