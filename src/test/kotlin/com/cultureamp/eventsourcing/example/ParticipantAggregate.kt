package com.cultureamp.eventsourcing.example

import com.cultureamp.eventsourcing.*
import org.joda.time.DateTime
import com.cultureamp.eventsourcing.example.State.*
import com.cultureamp.eventsourcing.sample.StandardEventMetadata
import java.util.*

data class ParticipantAggregate(val state: State) {
    companion object {
        @Suppress("UNUSED_PARAMETER")
        fun created(event: Invited): ParticipantAggregate = ParticipantAggregate(INVITED)

        fun create(command: Invite, metadata: StandardEventMetadata): Either<ParticipantError, Invited> = with(command) {
            Right(Invited(surveyPeriodId, employeeId, invitedAt))
        }
    }

    fun updated(event: ParticipantUpdateEvent): ParticipantAggregate = when(event) {
        is Invited -> this.copy(state = INVITED)
        is Uninvited -> this.copy(state = UNINVITED)
        is Reinvited -> this.copy(state = INVITED)
        else -> this
    }

    fun update(command: ParticipantUpdateCommand, metadata: StandardEventMetadata): Either<ParticipantError, List<ParticipantUpdateEvent>> =
        when (command) {
            is Invite -> when (state) {
                INVITED -> Left(AlreadyInvitedException)
                UNINVITED -> with(command) { Right.list(Reinvited(invitedAt)) }
            }
            is Uninvite -> when (state) {
                UNINVITED -> Left(AlreadyUninvitedException)
                INVITED -> Right.list(Uninvited(command.uninvitedAt))
            }
            is Reinvite -> Right.list(Reinvited(command.reinvitedAt))
            is Rereinvite -> Right.list(Rereinvited(command.reinvitedAt))
        }

}

enum class State {
    INVITED, UNINVITED
}

sealed class ParticipantCommand : Command
sealed class ParticipantUpdateCommand : ParticipantCommand(), UpdateCommand
data class Invite(
    override val aggregateId: UUID,
    val surveyPeriodId: UUID,
    val employeeId: UUID,
    val invitedAt: DateTime
) : ParticipantUpdateCommand(), CreationCommand

data class Uninvite(override val aggregateId: UUID, val uninvitedAt: DateTime) : ParticipantUpdateCommand()
data class Reinvite(override val aggregateId: UUID, val reinvitedAt: DateTime) : ParticipantUpdateCommand()
data class Rereinvite(override val aggregateId: UUID, val reinvitedAt: DateTime) : ParticipantUpdateCommand()

sealed class ParticipantEvent : DomainEvent
sealed class ParticipantUpdateEvent : ParticipantEvent(), UpdateEvent
data class Invited(val surveyPeriodId: UUID, val employeeId: UUID, val invitedAt: DateTime) : ParticipantUpdateEvent(),
    CreationEvent

data class Uninvited(val uninvitedAt: DateTime) : ParticipantUpdateEvent()
data class Reinvited(val reinvitedAt: DateTime) : ParticipantUpdateEvent()

@UpcastEvent(RereinvitedUpcast::class)
data class Rereinvited(val reinvitedAt: DateTime) : ParticipantUpdateEvent()
object RereinvitedUpcast : Upcast<StandardEventMetadata, Rereinvited, Reinvited>{
    override fun upcast(event: Rereinvited, metadata: StandardEventMetadata) = Reinvited(event.reinvitedAt)
}

sealed class ParticipantError : DomainError
object AlreadyInvitedException : ParticipantError(), AlreadyActionedCommandError
object AlreadyUninvitedException : ParticipantError(), AlreadyActionedCommandError