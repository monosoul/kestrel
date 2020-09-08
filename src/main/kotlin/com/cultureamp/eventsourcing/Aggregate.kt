package com.cultureamp.eventsourcing

import org.joda.time.DateTime
import java.util.*

interface BaseAggregate {
    fun aggregateType(): String = this::class.simpleName!!
}

interface Aggregate<UC: UpdateCommand, UE: UpdateEvent, Err: DomainError, out Self : Aggregate<UC, UE, Err, Self>> : BaseAggregate {
    fun updated(event: UE): Self
    fun update(command: UC): Either<Err, List<UE>>

    companion object {
        fun <UC : UpdateCommand, UE : UpdateEvent, Err: DomainError, A : Any> from(
            aggregate: A,
            update: A.(UC) -> Either<Err, List<UE>>,
            updated: A.(UE) -> A = { _ -> this },
            aggregateType: A.() -> String = { this::class.simpleName!! }
        ): Aggregate<UC, UE, Err, Aggregate<UC, UE, Err, *>> {
            return object : Aggregate<UC, UE, Err, Aggregate<UC, UE, Err, *>> {
                override fun updated(event: UE): Aggregate<UC, UE, Err, *> {
                    val updatedAggregate = aggregate.updated(event)
                    return from(updatedAggregate, update, updated)
                }

                override fun update(command: UC): Either<Err, List<UE>> {
                    return aggregate.update(command)
                }

                override fun aggregateType(): String {
                    return aggregate.aggregateType()
                }
            }
        }
    }
}

interface AggregateWithProjection<UC: UpdateCommand, UE: UpdateEvent, Err: DomainError, P, Self : AggregateWithProjection<UC, UE, Err, P, Self>>: BaseAggregate {
    fun updated(event: UE): Self
    fun update(projection: P, command: UC): Either<Err, List<UE>>

    fun partial(projection: P): Aggregate<UC, UE, Err, Aggregate<UC, UE, Err, *>> {
        return object:Aggregate<UC, UE, Err, Aggregate<UC, UE, Err, *>> {

            override fun updated(event: UE): Aggregate<UC, UE, Err, *> {
                return this@AggregateWithProjection.updated(event).partial(projection)
            }

            override fun update(command: UC): Either<Err, List<UE>> {
                return update(projection, command)
            }

            override fun aggregateType(): String = this@AggregateWithProjection.aggregateType()
        }
    }
}

interface AggregateConstructor<CC: CreationCommand, CE: CreationEvent, Err: DomainError, UC: UpdateCommand, UE: UpdateEvent, Self: Aggregate<UC, UE, Err, Self>> {
    fun created(event: CE): Self
    fun create(command: CC): Either<Err, CE>

    companion object {
        fun <CC: CreationCommand, CE: CreationEvent, Err: DomainError, UC: UpdateCommand, UE: UpdateEvent, A: Any> from(
            create: (CC) -> Either<Err, CE>,
            update: A.(UC) -> Either<Err, List<UE>>,
            created: (CE) -> A,
            updated: A.(UE) -> A = { _ -> this },
            aggregateType: A.() -> String = { this::class.simpleName!! }
        ): AggregateConstructor<CC, CE, Err, UC, UE, Aggregate<UC, UE, Err, *>> {

            return object : AggregateConstructor<CC, CE, Err, UC, UE, Aggregate<UC, UE, Err, *>> {
                override fun created(event: CE): Aggregate<UC, UE, Err, *> {
                    val createdAggregate = created(event)
                    return Aggregate.from(createdAggregate, update, updated, aggregateType)
                }

                override fun create(command: CC): Either<Err, CE> = create(command)
            }
        }
    }
}

interface AggregateConstructorWithProjection<CC: CreationCommand, CE: CreationEvent, Err: DomainError, UC: UpdateCommand, UE: UpdateEvent, P, Self : AggregateWithProjection<UC, UE, Err, P, Self>> {
    fun created(event: CE): Self
    fun create(projection: P, command: CC): Either<Err, CE>
    fun partial(projection: P): AggregateConstructor<CC, CE, Err, UC, UE, Aggregate<UC, UE, Err, *>> {
        return object:AggregateConstructor<CC, CE, Err, UC, UE, Aggregate<UC, UE, Err, *>> {
            override fun created(event: CE): Aggregate<UC, UE, Err, *> {
                return this@AggregateConstructorWithProjection.created(event).partial(projection)
            }

            override fun create(command: CC): Either<Err, CE> {
                return create(projection, command)
            }
        }
    }
}

internal fun <CC: CreationCommand, CE: CreationEvent, Err: DomainError, UC: UpdateCommand, UE: UpdateEvent> AggregateConstructor<CC, CE, Err, UC, UE, Aggregate<UC, UE, Err, *>>.create(
    creationCommand: CC,
    metadata: EventMetadata,
    eventStore: EventStore
): Either<CommandError, Unit> {
    return create(creationCommand).map { domainEvent ->
        val aggregate = created(domainEvent)
        val event = Event(
            id = UUID.randomUUID(),
            aggregateId = creationCommand.aggregateId,
            aggregateSequence = 1,
            createdAt = DateTime.now(),
            metadata = metadata,
            domainEvent = domainEvent
        )
        eventStore.sink(listOf(event), creationCommand.aggregateId, aggregate.aggregateType())
    }.flatten()
}

@Suppress("UNCHECKED_CAST")
internal fun <CC: CreationCommand, CE: CreationEvent, Err: DomainError, UC: UpdateCommand, UE: UpdateEvent> AggregateConstructor<CC, CE, Err, UC, UE, Aggregate<UC, UE, Err, *>>.update(
    updateCommand: UC,
    metadata: EventMetadata,
    events: List<Event>,
    eventStore: EventStore
): Either<CommandError, Unit> {
    val creationEvent = events.first().domainEvent as CreationEvent
    val updateEvents = events.slice(1 until events.size).map { it.domainEvent as UpdateEvent }
    val aggregate = rehydrated(creationEvent as CE, updateEvents as List<UE>)
    return aggregate.update(updateCommand).map { domainEvents ->
        val updated = aggregate.updated(domainEvents)
        val offset = events.last().aggregateSequence + 1
        val createdAt = DateTime()
        val storableEvents = domainEvents.withIndex().map { (index, domainEvent) ->
            Event(
                id = UUID.randomUUID(),
                aggregateId = updateCommand.aggregateId,
                aggregateSequence = offset + index,
                createdAt = createdAt,
                metadata = metadata,
                domainEvent = domainEvent
            )
        }
        eventStore.sink(storableEvents, updateCommand.aggregateId, updated.aggregateType())
    }.flatten()
}

internal fun <CC: CreationCommand, CE: CreationEvent, Err: DomainError, UC: UpdateCommand, UE: UpdateEvent> AggregateConstructor<CC, CE, Err, UC, UE, Aggregate<UC, UE, Err, *>>.rehydrated(
    creationEvent: CE,
    updateEvents: List<UE>
): Aggregate<UC, UE, Err, *> {
    return created(creationEvent).updated(updateEvents)
}

@Suppress("UNCHECKED_CAST")
private fun <UC: UpdateCommand, UE: UpdateEvent, Err: DomainError> Aggregate<UC, UE, Err, *>.updated(updateEvents: List<UE>): Aggregate<UC, UE, Err, *> {
    return updateEvents.fold(this) { aggregate, updateEvent -> aggregate.updated(updateEvent) as Aggregate<UC, UE, Err, *> }
}