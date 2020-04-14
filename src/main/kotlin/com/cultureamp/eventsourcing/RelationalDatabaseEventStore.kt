package com.cultureamp.eventsourcing

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.PropertyNamingStrategy
import com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS
import com.fasterxml.jackson.datatype.joda.JodaModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.vendors.H2Dialect
import org.jetbrains.exposed.sql.vendors.PostgreSQLDialect
import java.lang.UnsupportedOperationException
import java.util.*
import kotlin.reflect.full.companionObjectInstance

interface MetadataClassProvider {
    val metadataClass: Class<out EventMetadata>
}

val defaultObjectMapper = ObjectMapper()
    .registerKotlinModule()
    .registerModule(JodaModule())
    .configure(WRITE_DATES_AS_TIMESTAMPS, false)
    .setPropertyNamingStrategy(PropertyNamingStrategy.SNAKE_CASE)

class RelationalDatabaseEventStore internal constructor(
    private val db: Database,
    private val events: Events,
    synchronousProjectors: List<EventListener>,
    private val defaultMetadataClass: Class<out EventMetadata>
) : EventStore {

    private var _objectMapper = defaultObjectMapper
    var objectMapper: ObjectMapper
        get() = _objectMapper
        set(value) {
            _objectMapper = value
        }

    companion object {
        fun create(
            synchronousProjectors: List<EventListener>,
            db: Database,
            defaultMetadataClass: Class<out EventMetadata> = EventMetadata::class.java
        ): RelationalDatabaseEventStore =
            when (db.dialect) {
                is H2Dialect -> H2DatabaseEventStore.create(synchronousProjectors, db, defaultMetadataClass)
                is PostgreSQLDialect -> PostgresDatabaseEventStore.create(
                    synchronousProjectors,
                    db,
                    defaultMetadataClass
                )
                else -> throw UnsupportedOperationException("${db.dialect} not currently supported")
            }

        fun create(db: Database, defaultMetadataClass: Class<out EventMetadata> = EventMetadata::class.java) =
            create(emptyList(), db, defaultMetadataClass)
    }

    override val listeners: MutableList<EventListener> = synchronousProjectors.toMutableList()

    fun createSchema() {
        transaction(db) {
            // TODO don't do this if pointing directly to Murmur DB or potentially introduce separate migrations
            SchemaUtils.create(events)
        }
    }


    override fun sink(newEvents: List<Event>, aggregateId: UUID, aggregateType: String): Either<CommandError, Unit> {
        return try {
            return transaction(db) {
                newEvents.forEach { event ->
                    val body = objectMapper.writeValueAsString(event.domainEvent)
                    val eventType = event.domainEvent.javaClass
                    val metadata = objectMapper.writeValueAsString(event.metadata)
                    validateSerialization(eventType, body, metadata)
                    events.insert { row ->
                        row[events.aggregateSequence] = event.aggregateSequence
                        row[events.eventId] = event.id
                        row[events.aggregateId] = aggregateId
                        row[events.aggregateType] = aggregateType
                        row[events.eventType] = eventType.canonicalName
                        row[events.createdAt] = event.createdAt
                        row[events.body] = body
                        row[events.metadata] = metadata
                    }
                }

                notifyListeners(newEvents, aggregateId)
                Right(Unit)
            }
        } catch (e: ExposedSQLException) {
            if (e.message.orEmpty().contains("violates unique constraint")) {
                Left(ConcurrencyError)
            } else {
                throw e
            }
        }
    }

    private fun validateSerialization(eventType: Class<DomainEvent>, body: String, metadata: String) {
        // prove that json body can be deserialized, which catches invalid fields types, e.g. interfaces
        try {
            objectMapper.readValue<DomainEvent>(body, eventType)
        } catch (e: JsonProcessingException) {
            throw EventBodySerializationException(e)
        }

        try {
            objectMapper.readValue(metadata, metadataClassFor(eventType))
        } catch (e: JsonProcessingException) {
            throw EventMetadataSerializationException(e)
        }
    }

    private fun rowToSequencedEvent(row: ResultRow): SequencedEvent = row.let {
        val eventType = row[events.eventType].asClass<DomainEvent>()!!
        val domainEvent = objectMapper.readValue(row[events.body], eventType)
        val metadata = objectMapper.readValue(row[events.metadata], metadataClassFor(eventType))

        SequencedEvent(
            Event(
                id = row[events.eventId],
                aggregateId = row[events.aggregateId],
                aggregateSequence = row[events.aggregateSequence],
                createdAt = row[events.createdAt],
                metadata = metadata,
                domainEvent = domainEvent
            ), row[events.sequence]
        )
    }

    override fun replay(aggregateType: String, project: (Event) -> Unit) {
        return transaction(db) {
            events
                .select {
                    events.aggregateType eq aggregateType
                }
                .orderBy(events.sequence)
                .mapLazy(::rowToSequencedEvent)
                .mapLazy { it.event }
                .forEach(project)
        }
    }

    override fun getAfter(sequence: Long, batchSize: Int): List<SequencedEvent> {
        return transaction(db) {
            events
                .select {
                    events.sequence greater sequence
                }
                .orderBy(events.sequence)
                .limit(batchSize)
                .map(::rowToSequencedEvent)
        }
    }

    override fun eventsFor(aggregateId: UUID): List<Event> {
        return transaction(db) {
            events
                .select { events.aggregateId eq aggregateId }
                .orderBy(events.sequence)
                .map(::rowToSequencedEvent)
                .map { it.event }
        }
    }

    private fun metadataClassFor(eventType: Class<out DomainEvent>): Class<out EventMetadata> {
        return try {
            (eventType.kotlin.companionObjectInstance as MetadataClassProvider).metadataClass
        } catch (_: ClassCastException) {
            defaultMetadataClass
        }
    }
}

open class EventDataException(e: Exception) : Throwable(e)
class EventBodySerializationException(e: Exception) : EventDataException(e)
class EventMetadataSerializationException(e: Exception) : EventDataException(e)

object PostgresDatabaseEventStore {
    internal fun create(
        synchronousProjectors: List<EventListener>,
        db: Database,
        defaultMetadataClass: Class<out EventMetadata>
    ): RelationalDatabaseEventStore {
        return RelationalDatabaseEventStore(db, Events(Table::jsonb), synchronousProjectors, defaultMetadataClass)
    }
}

object H2DatabaseEventStore {
    internal fun create(
        synchronousProjectors: List<EventListener>,
        db: Database,
        defaultMetadataClass: Class<out EventMetadata>
    ): RelationalDatabaseEventStore {
        return RelationalDatabaseEventStore(db, eventsTable(), synchronousProjectors, defaultMetadataClass)
    }

    internal fun eventsTable() = Events { name -> this.text(name) }
}

private fun <T> String.asClass(): Class<out T>? {
    @Suppress("UNCHECKED_CAST")
    return Class.forName(this) as Class<out T>?
}

class Events(jsonb: Table.(String) -> Column<String>) : Table() {
    val sequence = long("sequence").autoIncrement().index()
    val eventId = uuid("id")
    val aggregateSequence = long("aggregate_sequence").primaryKey(1)
    val aggregateId = uuid("aggregate_id").primaryKey(0)
    val aggregateType = varchar("aggregate_type", 128)
    val eventType = varchar("event_type", 128)
    val createdAt = datetime("created_at")
    val body = jsonb("json_body")
    val metadata = jsonb("metadata")
}

object ConcurrencyError : RetriableError
