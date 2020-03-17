import com.cultureamp.eventsourcing.*
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.StdOutSqlLogger
import org.jetbrains.exposed.sql.addLogger
import org.jetbrains.exposed.sql.transactions.transaction
import survey.demo.*
import survey.design.*
import survey.thing.AlwaysBoppable
import survey.thing.ThingAggregate

fun main() {
    val eventStoreDatabase = Database.connect(DatabaseConfig.fromEnvironment("EVENT_STORE").toDataSource("event_store"))
    transaction(eventStoreDatabase) {
        addLogger(StdOutSqlLogger)
    }

    val (surveyNamesCommandQuery, surveyNamesCommandProjector) = SurveyNamesCommandProjection.create(eventStoreDatabase)
    val (_, surveyCommandProjector) = SurveyCommandProjection.create(eventStoreDatabase)

    val thingCommandProjection = AlwaysBoppable

    val registry = listOf(
        Configuration.from(
            SurveyCaptureLayoutAggregate.Companion::create,
            SurveyCaptureLayoutAggregate::update,
            SurveyCaptureLayoutAggregate.Companion::created,
            SurveyCaptureLayoutAggregate::updated
        ),
        Configuration.from(
            SurveyAggregate.Companion::create.partial(surveyNamesCommandQuery),
            SurveyAggregate::update.partial2(surveyNamesCommandQuery),
            ::SurveyAggregate,
            SurveyAggregate::updated
        ),
        Configuration.from(
            SurveySagaAggregate.Companion::create,
            SurveySagaAggregate::update,
            ::SurveySagaAggregate
        ),
        Configuration.from(
            ParticipantAggregate.Companion::create,
            ParticipantAggregate::update,
            ParticipantAggregate.Companion::created,
            ParticipantAggregate::updated
        ),
        Configuration.from(
            PaymentSagaAggregate::create,
            PaymentSagaAggregate::update,
            PaymentSagaAggregate
        ),
        Configuration.from(ThingAggregate, thingCommandProjection)
    )
    val eventStore = PostgresDatabaseEventStore.create(eventStoreDatabase)
    eventStore.setup()
    val commandGateway = CommandGateway(eventStore, registry)
    val paymentService = PaymentService()
    val emailService = EmailService()
    val paymentSagaReactor = PaymentSagaReactor(commandGateway, paymentService, emailService, eventStoreDatabase);
    paymentSagaReactor.setup()
    val surveySagaReactor = SurveySagaReactor(commandGateway)

    // TODO this should be done as separate threads/workers that poll the event-store
    eventStore.listeners.addAll(listOf(
        EventListener.from(paymentSagaReactor::react),
        EventListener.from(surveySagaReactor::react),
        EventListener.from(surveyNamesCommandProjector::project),
        EventListener.from(surveyCommandProjector::first, surveyCommandProjector::second)
    ))

    Ktor.startEmbeddedCommandServer(commandGateway, eventStore)
}
