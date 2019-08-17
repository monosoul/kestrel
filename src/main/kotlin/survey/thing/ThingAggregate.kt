package survey.thing

import eventsourcing.*
import survey.design.SurveyCaptureLayoutAggregate
import survey.design.SurveyCaptureLayoutCommandError
import survey.design.SurveyCaptureLayoutCreationEvent
import survey.design.SurveyCreationCommand
import java.util.UUID

sealed class ThingCreationCommand : CreationCommand
data class CreateThing(override val aggregateId: UUID) : ThingCreationCommand()

sealed class ThingUpdateCommand : UpdateCommand
data class Tweak(override val aggregateId: UUID, val tweak: String) : ThingUpdateCommand()
data class Bop(override val aggregateId: UUID) : ThingUpdateCommand()

sealed class ThingCreationEvent : CreationEvent
data class ThingCreated(override val aggregateId: UUID) : ThingCreationEvent()

sealed class ThingUpdateEvent : UpdateEvent
data class Tweaked(override val aggregateId: UUID, val tweak: String) : ThingUpdateEvent()
data class Bopped(override val aggregateId: UUID) : ThingUpdateEvent()

data class ThingAggregate(override val aggregateId: UUID, val tweaks: List<String> = emptyList(), val bops: List<Bopped> = emptyList()) : Aggregate<ThingUpdateCommand, ThingUpdateEvent, CommandError, ThingAggregate> {
    companion object : AggregateConstructor<ThingCreationCommand, ThingCreationEvent, CommandError, ThingUpdateCommand, ThingUpdateEvent, ThingAggregate> {
        override fun create(command: ThingCreationCommand): Either<CommandError, ThingCreationEvent> = when(command){
            is CreateThing -> Right(ThingCreated(command.aggregateId))
        }

        override fun created(event: ThingCreationEvent): ThingAggregate = when(event) {
            is ThingCreated -> ThingAggregate(event.aggregateId)
        }
    }

    override fun updated(event: ThingUpdateEvent): ThingAggregate = when(event){
        is Tweaked -> this.copy(tweaks = tweaks + event.tweak)
        is Bopped -> this.copy(bops = bops + event)
    }

    override fun update(command: ThingUpdateCommand): Either<CommandError, List<ThingUpdateEvent>> = when(command) {
        is Tweak -> Right.list(Tweaked(command.aggregateId, command.tweak))
        is Bop -> Right.list(Bopped(command.aggregateId))
    }
}
