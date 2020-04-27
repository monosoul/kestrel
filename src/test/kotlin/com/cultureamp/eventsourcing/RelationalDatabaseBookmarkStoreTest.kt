package com.cultureamp.eventsourcing

import io.kotest.core.spec.style.DescribeSpec
import org.jetbrains.exposed.sql.Database
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction

class RelationalDatabaseBookmarkStoreTest : DescribeSpec({
    val h2DbUrl = "jdbc:h2:mem:test;MODE=MySQL;DB_CLOSE_DELAY=-1;"
    val h2Driver = "org.h2.Driver"
    val db = Database.connect(url = h2DbUrl, driver = h2Driver)
    val table = Bookmarks()
    val store = RelationalDatabaseBookmarkStore(db, table)

    beforeTest {
        transaction(db) {
            SchemaUtils.create(table)
        }
    }

    afterTest {
        transaction(db) {
            SchemaUtils.drop(table)
        }
    }

    describe("RelationalDatabaseBookmarkStore") {
        it("sets and retrieves a bookmark") {
            store.save("new-bookmark", Bookmark(123L))
            store.findOrCreate("new-bookmark") shouldBe Bookmark(123L)
        }

        it("returns zero for an unknown bookmark") {
            store.findOrCreate("other-new-bookmark") shouldBe Bookmark(0L)
        }

        it("updates the value if the bookmark already exists") {
            store.save("update-bookmark", Bookmark(123L))
            store.findOrCreate("update-bookmark") shouldBe Bookmark(123L)
            store.save("update-bookmark", Bookmark(456L))
            store.findOrCreate("update-bookmark") shouldBe Bookmark(456L)
        }
    }
})


