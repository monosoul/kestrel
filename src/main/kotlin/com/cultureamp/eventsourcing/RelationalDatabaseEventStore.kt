package com.cultureamp.eventsourcing

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS
import com.fasterxml.jackson.datatype.joda.JodaModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.jodatime.datetime
import org.jetbrains.exposed.sql.max
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.vendors.H2Dialect
import org.jetbrains.exposed.sql.vendors.PostgreSQLDialect
import org.postgresql.util.PSQLException
import java.util.UUID
import kotlin.reflect.KClass

val defaultObjectMapper = ObjectMapper()
    .registerKotlinModule()
    .registerModule(JodaModule())
    .configure(WRITE_DATES_AS_TIMESTAMPS, false)
    .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)

val defaultTableName = "events"

class RelationalDatabaseEventStore<M : EventMetadata> @PublishedApi internal constructor(
    private val db: Database,
    private val events: Events,
    private val synchronousEventProcessors: List<EventProcessor<M>>,
    private val metadataClass: Class<M>,
    private val objectMapper: ObjectMapper,
    private val blockingLockUntilTransactionEnd: Transaction.() -> CommandError? = { null }
) : EventStore<M> {

    companion object {
        inline fun <reified M : EventMetadata> create(
            synchronousEventProcessors: List<EventProcessor<M>>,
            db: Database,
            objectMapper: ObjectMapper = defaultObjectMapper,
            tableName: String = defaultTableName
        ): RelationalDatabaseEventStore<M> =
            when (db.dialect) {
                is H2Dialect -> H2DatabaseEventStore.create(synchronousEventProcessors, db, objectMapper, tableName)
                is PostgreSQLDialect -> PostgresDatabaseEventStore.create(synchronousEventProcessors, db, objectMapper, tableName)
                else -> throw UnsupportedOperationException("${db.dialect} not currently supported")
            }

        inline fun <reified M : EventMetadata> create(
            db: Database,
            objectMapper: ObjectMapper = defaultObjectMapper,
            tableName: String = defaultTableName
        ) =
            create<M>(emptyList(), db, objectMapper, tableName)
    }

    fun createSchemaIfNotExists() {
        transaction(db) {
            SchemaUtils.create(events)
        }
    }

    override fun sink(newEvents: List<Event<M>>, aggregateId: UUID): Either<CommandError, Unit> {
        return try {
            return transaction(db) {
                blockingLockUntilTransactionEnd()?.let { Left(it) } ?: run {
                    newEvents.forEach { event ->
                        val body = objectMapper.writeValueAsString(event.domainEvent)
                        val eventType = event.domainEvent.javaClass
                        val metadata = objectMapper.writeValueAsString(event.metadata)
                        validateSerialization(eventType, body, metadata)
                        events.insert { row ->
                            row[events.aggregateSequence] = event.aggregateSequence
                            row[events.eventId] = event.id
                            row[events.aggregateId] = aggregateId
                            row[events.aggregateType] = event.aggregateType
                            row[events.eventType] = eventType.canonicalName
                            row[events.createdAt] = event.createdAt
                            row[events.body] = body
                            row[events.metadata] = metadata
                        }
                    }

                    updateSynchronousProjections(newEvents)
                    Right(Unit)
                }
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
            objectMapper.readValue(metadata, metadataClass)
        } catch (e: JsonProcessingException) {
            throw EventMetadataSerializationException(e)
        }
    }

    private fun rowToSequencedEvent(row: ResultRow): SequencedEvent<M> = row.let {
        val eventType = row[events.eventType].asClass<DomainEvent>()!!
        val domainEvent = objectMapper.readValue(row[events.body], eventType)
        val metadata = objectMapper.readValue(row[events.metadata], metadataClass)

        SequencedEvent(
            Event(
                id = row[events.eventId],
                aggregateId = row[events.aggregateId],
                aggregateSequence = row[events.aggregateSequence],
                aggregateType = row[events.aggregateType],
                createdAt = row[events.createdAt],
                metadata = metadata,
                domainEvent = domainEvent
            ),
            row[events.sequence]
        )
    }

    override fun getAfter(sequence: Long, eventClasses: List<KClass<out DomainEvent>>, batchSize: Int): List<SequencedEvent<M>> {
        return transaction(db) {
            events
                .select {
                    val eventTypeMatches = if (eventClasses.isNotEmpty()) {
                        events.eventType.inList(eventClasses.map { it.java.canonicalName })
                    } else {
                        Op.TRUE
                    }
                    events.sequence greater sequence and eventTypeMatches
                }
                .orderBy(events.sequence)
                .limit(batchSize)
                .map(::rowToSequencedEvent)
        }
    }

    override fun eventsFor(aggregateId: UUID): List<Event<M>> {
        return transaction(db) {
            events
                .select { events.aggregateId eq aggregateId }
                .orderBy(events.sequence)
                .map(::rowToSequencedEvent)
                .map { it.event }
        }
    }

    override fun lastSequence(eventClasses: List<KClass<out DomainEvent>>): Long = transaction(db) {
        val maxSequence = events.sequence.max()
        events
            .slice(maxSequence)
            .select {
                if (eventClasses.isNotEmpty()) {
                    events.eventType.inList(eventClasses.map { it.java.canonicalName })
                } else {
                    Op.TRUE
                }
            }
            .map { it[maxSequence] }
            .first() ?: 0
    }

    private fun updateSynchronousProjections(newEvents: List<Event<out M>>) {
        newEvents.forEach { event -> synchronousEventProcessors.forEach { it.process(event) } }
    }
}

open class EventDataException(e: Exception) : Throwable(e)
class EventBodySerializationException(e: Exception) : EventDataException(e)
class EventMetadataSerializationException(e: Exception) : EventDataException(e)

object PostgresDatabaseEventStore {
    @PublishedApi
    internal inline fun <reified M : EventMetadata> create(
        synchronousEventProcessors: List<EventProcessor<M>>,
        db: Database,
        objectMapper: ObjectMapper,
        tableName: String
    ): RelationalDatabaseEventStore<M> {
        return RelationalDatabaseEventStore(db, Events(tableName, Table::jsonb), synchronousEventProcessors, M::class.java, objectMapper, Transaction::pgAdvisoryXactLock)
    }
}

object H2DatabaseEventStore {
    // need a `@PublishedApi` here to make it callable from `RelationalDatabaseEventStore.create()`
    @PublishedApi
    internal inline fun <reified M : EventMetadata> create(
        synchronousEventProcessors: List<EventProcessor<M>>,
        db: Database,
        objectMapper: ObjectMapper,
        tableName: String
    ): RelationalDatabaseEventStore<M> {
        return RelationalDatabaseEventStore(db, eventsTable(tableName), synchronousEventProcessors, M::class.java, objectMapper)
    }

    @PublishedApi
    internal fun eventsTable(tableName: String = defaultTableName) = Events(tableName) { name -> this.text(name) }
}

private fun <T> String.asClass(): Class<out T>? {
    @Suppress("UNCHECKED_CAST")
    return Class.forName(this) as Class<out T>?
}

class Events(tableName: String = defaultTableName, jsonb: Table.(String) -> Column<String> = Table::jsonb) :
    Table(tableName) {
    val sequence = long("sequence").autoIncrement()
    val eventId = uuid("id")
    val aggregateSequence = long("aggregate_sequence")
    val aggregateId = uuid("aggregate_id")
    val aggregateType = varchar("aggregate_type", 128)
    val eventType = varchar("event_type", 256)
    val createdAt = datetime("created_at")
    val body = jsonb("json_body")
    val metadata = jsonb("metadata")
    override val primaryKey: PrimaryKey = PrimaryKey(sequence)

    init {
        uniqueIndex(eventId)
        uniqueIndex(aggregateId, aggregateSequence)
        nonUniqueIndex(eventType, aggregateType)
    }
}

private fun Table.nonUniqueIndex(vararg columns: Column<*>) = index(false, *columns)

object ConcurrencyError : RetriableError
object LockingError : CommandError

fun Transaction.pgAdvisoryXactLock(): CommandError? {
    val lockTimeoutMilliseconds = 10_000
    try {
        exec("SET LOCAL lock_timeout = '${lockTimeoutMilliseconds}ms';")
        exec("SELECT pg_advisory_xact_lock(-1)")
    } catch (e: PSQLException) {
        if (e.message.orEmpty().contains("canceling statement due to lock timeout")) {
            return LockingError
        } else {
            throw e
        }
    }
    return null
}
