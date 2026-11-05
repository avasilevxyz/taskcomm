package xyz.avasilev.taskcomm.communication.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import xyz.avasilev.taskcomm.communication.api.CommunicationCancelResult;
import xyz.avasilev.taskcomm.communication.api.CommunicationRestartResult;
import xyz.avasilev.taskcomm.communication.api.DedupKey;
import xyz.avasilev.taskcomm.communication.api.ExchangeMessage;

/**
 * Отмена и ручной перезапуск коммуникаций (docs/adr/0139-restart-api.md): ждущая отменяется сразу,
 * работающая получает только отметку, терминальная не отменяется; перезапускается упавшая — заново
 * с обработки, с копией запроса у входящей, с открытыми снова ключами дедупликации.
 */
class CommunicationAdministrationIT {

    private static final PollerSettings MAIN = CommunicationTestSettings.MAIN;

    private static final Map<String, ContractShape> KAFKA = Map.of("stock", ContractShape.KAFKA);

    private JdbcClient jdbc;

    private CommunicationCreator creator;

    private CommunicationClaimer claimer;

    private CommunicationAdministration administration;

    @BeforeEach
    void createSchema() {
        final CommunicationTestDatabase db = CommunicationTestDatabase.create();
        this.jdbc = db.jdbc;
        this.creator = db.creator();
        final CommunicationSettings settings = CommunicationSettings.resolve(
                CommunicationTestSettings.global(3, Duration.ZERO, Duration.ofMinutes(1)),
                Map.of(),
                new CommunicationProcessorRegistry(List.of(new CommunicationClaimIT.StockProcessor())),
                CommunicationTestSettings.POLLERS,
                Timing.DEFAULT);
        this.claimer = db.claimer(settings);
        this.administration =
                new CommunicationAdministration(db.dataSource, db.transactionManager, () -> settings, db.ids);
    }

    @Test
    void pendingIsCancelledAtOnceWithItsOpenAttempt() {
        final CreatedCommunication created = this.outbound();

        final CommunicationCancelResult result = this.administration.cancel(ref(created));

        assertThat(result.accepted()).as(result.reason()).isTrue();
        assertThat(this.value("SELECT status FROM tc_communication WHERE id = :id", created.id()))
                .isEqualTo("CANCELLED");
        assertThat(this.value("SELECT status FROM tc_communication_attempt WHERE id = :id", created.attemptId()))
                .isEqualTo("SKIPPED");
        assertThat(this.value(
                        "SELECT (finished_at IS NOT NULL)::text FROM tc_communication_dedup WHERE communication_id = :id",
                        created.id()))
                .isEqualTo("true");
        assertThat(this.administration.cancel(ref(created)).reason()).contains("уже отменена");
        assertThat(this.administration.restart(ref(created)).reason()).contains("отменённое не перезапускается");
    }

    @Test
    void runningOnlyGetsTheMark() {
        final CreatedCommunication created = this.outbound();
        assertThat(this.claimer.claim(MAIN, KAFKA, 1)).hasSize(1);

        assertThat(this.administration.cancel(ref(created)).reason()).contains("доиграет");

        assertThat(this.value("SELECT status FROM tc_communication WHERE id = :id", created.id()))
                .isEqualTo("RUNNING");
        assertThat(this.value(
                        "SELECT (cancel_requested_at IS NOT NULL)::text FROM tc_communication WHERE id = :id",
                        created.id()))
                .isEqualTo("true");
    }

    @Test
    void terminalIsNotCancelledAndOnlyFailedRestarts() {
        final CreatedCommunication created = this.outbound();
        this.fail(created);
        this.jdbc
                .sql("UPDATE tc_communication SET status = 'SUCCEEDED' WHERE id = :id")
                .param("id", created.id())
                .update();

        assertThat(this.administration.cancel(ref(created)).reason()).contains("терминальном статусе SUCCEEDED");
        assertThat(this.administration.restart(ref(created)).reason()).contains("только упавшая");
    }

    @Test
    void failedOutboundIsSentAgainInNewCycle() {
        final CreatedCommunication created = this.outbound();
        this.fail(created);

        final CommunicationRestartResult result = this.administration.restart(ref(created));

        assertThat(result.accepted()).as(result.reason()).isTrue();
        assertThat(this.value("SELECT status || '/' || phase FROM tc_communication WHERE id = :id", created.id()))
                .isEqualTo("PENDING/PROCESS");
        assertThat(this.value(
                        "SELECT (finished_at IS NULL AND succeeded_at IS NULL)::text FROM tc_communication_dedup"
                                + " WHERE communication_id = :id",
                        created.id()))
                .isEqualTo("true");
        final List<ClaimedCommunication> again = this.claimer.claim(MAIN, KAFKA, 1);
        assertThat(again).singleElement().satisfies(attempt -> {
            assertThat(attempt.lease().attemptId()).isNotEqualTo(created.attemptId());
            assertThat(attempt.history()).isEqualTo(new AttemptHistory(1, true));
        });
    }

    @Test
    void failedInboundIsProcessedAgainWithItsRequest() {
        final CreatedCommunication created = this.creator.create(new NewCommunication(
                Direction.INBOUND,
                "stock",
                "{\"sku\":1}",
                ContractShape.KAFKA,
                new NewCommunication.Scheduling(100, Duration.ofMinutes(1), null),
                new NewCommunication.Placement(null, null),
                new NewCommunication.Start(ExchangeMessage.of("{\"sku\":1}").withId("req-1"), null, null, null),
                NewCommunication.Keys.NONE));
        this.fail(created);

        assertThat(this.administration.restart(ref(created)).accepted()).isTrue();

        assertThat(this.value(
                        "SELECT request_id FROM tc_communication_attempt WHERE communication_id = :id"
                                + " AND finished_at IS NULL",
                        created.id()))
                .isEqualTo("req-1");
    }

    @Test
    void unparsedInboundIsNotRestarted() {
        final CreatedCommunication created = this.outbound();
        this.fail(created);
        this.jdbc
                .sql("UPDATE tc_communication SET request_payload = NULL WHERE id = :id")
                .param("id", created.id())
                .update();

        assertThat(this.administration.restart(ref(created)).reason()).contains("не разобрано");
    }

    private CreatedCommunication outbound() {
        return this.creator.create(new NewCommunication(
                Direction.OUTBOUND,
                "stock",
                "{\"sku\":1}",
                null,
                new NewCommunication.Scheduling(100, Duration.ofMinutes(1), null),
                new NewCommunication.Placement(null, null),
                NewCommunication.Start.OUTBOUND,
                new NewCommunication.Keys(List.of(new DedupKey.WholeHistory("k-" + UUID.randomUUID())), List.of())));
    }

    /** Попытка закрыта неуспехом, коммуникация {@code FAILED} — как после исчерпания попыток. */
    private void fail(final CreatedCommunication created) {
        this.jdbc.sql("""
                        UPDATE tc_communication_attempt
                           SET status = 'FAILED', started_at = coalesce(started_at, now()), finished_at = now()
                         WHERE communication_id = :id AND finished_at IS NULL
                        """).param("id", created.id()).update();
        this.jdbc
                .sql("UPDATE tc_communication SET status = 'FAILED' WHERE id = :id")
                .param("id", created.id())
                .update();
        CommunicationDedup.finish(this.jdbc, ref(created), false);
    }

    private String value(final String sql, final UUID id) {
        return this.jdbc.sql(sql).param("id", id).query(String.class).single();
    }

    private static CommunicationRef ref(final CreatedCommunication created) {
        return new CommunicationRef(created.id(), created.execution().createdAt());
    }
}
