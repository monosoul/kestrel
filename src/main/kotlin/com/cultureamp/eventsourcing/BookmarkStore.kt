package com.cultureamp.eventsourcing

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime

interface BookmarkStore {
    @Deprecated("Method name was misleading", ReplaceWith("bookmarkFor"))
    fun findOrCreate(bookmarkName: String) = bookmarkFor(bookmarkName)
    fun bookmarkFor(bookmarkName: String): Bookmark
    fun save(bookmarkName: String, bookmark: Bookmark)
}

class RelationalDatabaseBookmarkStore(val db: Database, val table: Bookmarks = Bookmarks()) : BookmarkStore {
    override fun bookmarkFor(bookmarkName: String): Bookmark = transaction(db) {
        val matchingRows = rowsForBookmark(bookmarkName)
        val bookmarkVal = when (matchingRows.count()) {
            0 -> 0L
            else -> matchingRows.single()[table.sequence]
        }
        Bookmark(bookmarkVal)
    }

    override fun save(bookmarkName: String, bookmark: Bookmark): Unit = transaction(db) {
        when (rowsForBookmark(bookmarkName).count()) {
            0 -> table.insert {
                it[name] = bookmarkName
                it[sequence] = bookmark.sequence
                it[createdAt] = DateTime.now()
                it[updatedAt] = DateTime.now()

            }
            else -> table.update({ table.name eq bookmarkName }) {
                it[sequence] = bookmark.sequence
                it[updatedAt] = DateTime.now()
            }
        }
    }

    private fun rowsForBookmark(bookmarkName: String) = table.select { table.name eq bookmarkName }

}

class Bookmarks(name: String = "bookmarks") : Table(name) {
    val name = varchar("name", 160).primaryKey().uniqueIndex()
    val sequence = long("value")
    val createdAt = datetime("created_at")
    val updatedAt = datetime("updated_at")
}

data class Bookmark(val sequence: Long)

