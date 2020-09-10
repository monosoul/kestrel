package com.cultureamp.eventsourcing

import com.cultureamp.eventsourcing.sample.*
import com.cultureamp.eventsourcing.sample.PizzaStyle.MARGHERITA
import com.cultureamp.eventsourcing.sample.PizzaTopping.*
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.junit.jupiter.api.assertThrows
import java.util.*

class RelationalDatabaseEventStoreTest : DescribeSpec({
    val h2DbUrl = "jdbc:h2:mem:test;MODE=MySQL;DB_CLOSE_DELAY=-1;"
    val h2Driver = "org.h2.Driver"
    val db = Database.connect(url = h2DbUrl, driver = h2Driver)
    val table = H2DatabaseEventStore.eventsTable()
    val store =  RelationalDatabaseEventStore.create<StandardEventMetadata>(db)

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

    describe("RelationalDatabaseEventStore") {
        it("sets and retrieves multiple events") {
            val aggregateId = UUID.randomUUID()
            val otherAggregateId = UUID.randomUUID()
            val basicPizzaCreated = PizzaCreated(MARGHERITA, listOf(TOMATO_PASTE, BASIL, CHEESE))
            val firstPizzaCreated = event(
                basicPizzaCreated,
                aggregateId,
                1,
                StandardEventMetadata("alice", "123")
            )
            val firstPizzaEaten = event(
                PizzaEaten(),
                aggregateId,
                2,
                StandardEventMetadata("alice")
            )
            val secondPizzaCreated = event(
                basicPizzaCreated,
                otherAggregateId,
                1,
                StandardEventMetadata("bob", "321")
            )

            val events = listOf(firstPizzaCreated, firstPizzaEaten)
            val otherEvents = listOf(secondPizzaCreated)

            store.sink(events, aggregateId, "pizza") shouldBe Right(Unit)
            store.sink(otherEvents, otherAggregateId, "pizza") shouldBe Right(Unit)

            store.eventsFor(aggregateId) shouldBe events
            store.eventsFor(otherAggregateId) shouldBe otherEvents

            val expectedSequenceEvents = (events + otherEvents).mapIndexed { seq, ev -> SequencedEvent(ev, (seq + 1).toLong()) }
            store.getAfter(0L) shouldBe expectedSequenceEvents
            store.getAfter(0L, listOf(PizzaEaten::class)).map { it.event } shouldBe listOf(firstPizzaEaten)
        }

        it ("fails when the metadata passed in does not match the type specified for the store") {
            val aggregateId = UUID.randomUUID()
            val events = listOf(
                event(
                    PizzaToppingAdded(HAM),
                    aggregateId,
                    1,
                    EmptyMetadata()
                )
            )

            assertThrows<EventMetadataSerializationException> {
                store.sink(events, aggregateId, "pizza")
            }
        }

        it("exposes the latest sequence value") {
            val aggregateId = UUID.randomUUID()
            val events = listOf(
                event(PizzaCreated(MARGHERITA, listOf(TOMATO_PASTE)), aggregateId, 1, StandardEventMetadata("unused")),
                event(PizzaEaten(), aggregateId, 3, StandardEventMetadata("unused")),
                event(PizzaToppingAdded(CHEESE), aggregateId, 2, StandardEventMetadata("unused"))
            )

            store.lastSequence() shouldBe 0
            store.sink(events, aggregateId, "pizza") shouldBe Right(Unit)
            store.lastSequence() shouldBe 3
        }
    }
})

fun event(domainEvent: DomainEvent, aggregateId: UUID, index: Int, metadata: EventMetadata): Event {
    return Event(UUID.randomUUID(), aggregateId, index.toLong(), DateTime.now(), metadata, domainEvent)
}


