package xyz.avasilev.taskcomm.communication.internal.claim;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import xyz.avasilev.taskcomm.communication.api.Communication;
import xyz.avasilev.taskcomm.communication.api.ExchangeMessage;
import xyz.avasilev.taskcomm.communication.api.processor.CommunicationContext;
import xyz.avasilev.taskcomm.communication.api.processor.KafkaCommunicationProcessor;
import xyz.avasilev.taskcomm.communication.internal.AttemptHistory;
import xyz.avasilev.taskcomm.communication.internal.ClaimedCommunication;
import xyz.avasilev.taskcomm.communication.internal.CommunicationTestDatabase;
import xyz.avasilev.taskcomm.communication.internal.CommunicationTestSettings;
import xyz.avasilev.taskcomm.communication.internal.ContractShape;
import xyz.avasilev.taskcomm.communication.internal.Direction;
import xyz.avasilev.taskcomm.communication.internal.creation.CommunicationCreator;
import xyz.avasilev.taskcomm.communication.internal.creation.CreatedCommunication;
import xyz.avasilev.taskcomm.communication.internal.creation.NewCommunication;
import xyz.avasilev.taskcomm.communication.internal.processor.CommunicationProcessorRegistry;
import xyz.avasilev.taskcomm.communication.internal.settings.CommunicationSettings;
import xyz.avasilev.taskcomm.communication.internal.settings.PollerSettings;
import xyz.avasilev.taskcomm.communication.internal.settings.Timing;

/**
 * Захват коммуникаций на уровне хранилища (docs/adr/0054-communication-attempt-lifecycle.md,
 * docs/adr/0067-expired-lease-closes-attempt.md): аренда проставляется в существующую попытку,
 * брошенная попытка закрывается {@code LEASE_LOST} и следующая открывается с копией запроса,
 * несовпадение формы контракта отменяет коммуникацию, осиротевшие системы не трогаются.
 */
public class CommunicationClaimIT {

    private static final PollerSettings MAIN = CommunicationTestSettings.MAIN;

    private static final Map<String, ContractShape> KAFKA = Map.of("stock", ContractShape.KAFKA);

    private JdbcClient jdbc;

    private CommunicationCreator creator;

    private CommunicationClaimer claimer;

    @BeforeEach
    void createSchema() {
        final CommunicationTestDatabase db = CommunicationTestDatabase.create();
        this.jdbc = db.jdbc;
        this.creator = db.creator();
        this.claimer = db.claimer(CommunicationSettings.resolve(
                CommunicationTestSettings.global(3, Duration.ZERO, Duration.ofMinutes(1)),
                Map.of(),
                new CommunicationProcessorRegistry(List.of(new StockProcessor())),
                CommunicationTestSettings.POLLERS,
                Timing.DEFAULT));
    }

    @Test
    void claimLeasesExistingAttemptAndWritesShapeOfOutbound() {
        final CreatedCommunication created = this.create(Direction.OUTBOUND, "stock", null);

        final List<ClaimedCommunication> claimed = this.claimer.claim(MAIN, KAFKA, 10);

        assertThat(claimed).singleElement().satisfies(attempt -> {
            assertThat(attempt.lease().attemptId()).isEqualTo(created.attemptId());
            assertThat(attempt.history()).isEqualTo(new AttemptHistory(1, false));
        });
        assertThat(this.value("SELECT contract_shape FROM tc_communication WHERE id = :id", created.id()))
                .isEqualTo("KAFKA");
        assertThat(this.value("SELECT locked_by FROM tc_communication_attempt WHERE id = :id", created.attemptId()))
                .isEqualTo("test-instance");
        assertThat(this.claimer.claim(MAIN, KAFKA, 10)).isEmpty();
    }

    @Test
    void expiredAttemptIsClosedAndNextOneCopiesInboundRequest() {
        final CreatedCommunication created = this.create(Direction.INBOUND, "stock", ContractShape.KAFKA);
        this.claimer.claim(MAIN, KAFKA, 10);
        this.jdbc
                .sql("UPDATE tc_communication_attempt SET locked_until = now() - interval '1 second' WHERE id = :id")
                .param("id", created.attemptId())
                .update();

        final List<ClaimedCommunication> again = this.claimer.claim(MAIN, KAFKA, 10);

        assertThat(this.value("SELECT status FROM tc_communication_attempt WHERE id = :id", created.attemptId()))
                .isEqualTo("LEASE_LOST");
        assertThat(again).singleElement().satisfies(attempt -> {
            assertThat(attempt.lease().attemptId()).isNotEqualTo(created.attemptId());
            assertThat(attempt.history()).isEqualTo(new AttemptHistory(2, true));
            assertThat(this.value(
                            "SELECT request_body FROM tc_communication_attempt WHERE id = :id",
                            attempt.lease().attemptId()))
                    .isEqualTo("{\"sku\":1}");
        });
    }

    @Test
    void shapeMismatchCancelsCommunication() {
        final CreatedCommunication created = this.create(Direction.INBOUND, "stock", ContractShape.REST);

        assertThat(this.claimer.claim(MAIN, KAFKA, 10)).isEmpty();

        assertThat(this.value("SELECT status FROM tc_communication WHERE id = :id", created.id()))
                .isEqualTo("CANCELLED");
        assertThat(this.value("SELECT status_description FROM tc_communication WHERE id = :id", created.id()))
                .isEqualTo("форма контракта REST не совпадает с текущей KAFKA");
        assertThat(this.value("SELECT status FROM tc_communication_attempt WHERE id = :id", created.attemptId()))
                .isEqualTo("SKIPPED");
    }

    @Test
    void orphanSystemIsNeitherClaimedNorClosed() {
        final CreatedCommunication orphan = this.create(Direction.OUTBOUND, "gone", null);

        assertThat(this.claimer.claim(MAIN, KAFKA, 10)).isEmpty();

        assertThat(this.value("SELECT status FROM tc_communication WHERE id = :id", orphan.id()))
                .isEqualTo("PENDING");
    }

    private CreatedCommunication create(final Direction direction, final String system, final ContractShape shape) {
        return this.creator.create(new NewCommunication(
                direction,
                system,
                "{\"sku\":1}",
                shape,
                new NewCommunication.Scheduling(100, Duration.ofMinutes(1), null),
                new NewCommunication.Placement(null, null),
                direction == Direction.INBOUND
                        ? new NewCommunication.Start(ExchangeMessage.of("{\"sku\":1}"), null, null, null)
                        : NewCommunication.Start.OUTBOUND,
                NewCommunication.Keys.NONE));
    }

    private String value(final String sql, final UUID id) {
        return this.jdbc.sql(sql).param("id", id).query(String.class).single();
    }

    /** Система склада. */
    public static class StockProcessor implements KafkaCommunicationProcessor<Map<String, Integer>, Void, Void> {

        @Override
        public String externalSystem() {
            return "stock";
        }

        @Override
        public Void process(
                final Communication<Map<String, Integer>> communication, final CommunicationContext context) {
            return null;
        }
    }
}
