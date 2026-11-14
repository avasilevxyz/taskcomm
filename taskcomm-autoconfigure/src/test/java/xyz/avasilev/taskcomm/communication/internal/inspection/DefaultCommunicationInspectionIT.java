package xyz.avasilev.taskcomm.communication.internal.inspection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import xyz.avasilev.taskcomm.SqlQuery;
import xyz.avasilev.taskcomm.communication.api.CommunicationHeaders;
import xyz.avasilev.taskcomm.communication.api.CommunicationStatus;
import xyz.avasilev.taskcomm.communication.api.CorrelationKey;
import xyz.avasilev.taskcomm.communication.api.DedupKey;
import xyz.avasilev.taskcomm.communication.api.ExchangeMessage;
import xyz.avasilev.taskcomm.communication.api.inspection.CommunicationQuery;
import xyz.avasilev.taskcomm.communication.api.inspection.CommunicationSnapshot;
import xyz.avasilev.taskcomm.communication.api.inspection.CommunicationSummary;
import xyz.avasilev.taskcomm.communication.api.inspection.CorrelationKeySnapshot;
import xyz.avasilev.taskcomm.communication.api.inspection.DedupKeySnapshot;
import xyz.avasilev.taskcomm.communication.internal.CommunicationTestDatabase;
import xyz.avasilev.taskcomm.communication.internal.ContractShape;
import xyz.avasilev.taskcomm.communication.internal.Direction;
import xyz.avasilev.taskcomm.communication.internal.claim.CommunicationClaimIT;
import xyz.avasilev.taskcomm.communication.internal.creation.CommunicationCreator;
import xyz.avasilev.taskcomm.communication.internal.creation.CreatedCommunication;
import xyz.avasilev.taskcomm.communication.internal.creation.NewCommunication;
import xyz.avasilev.taskcomm.communication.internal.processor.CommunicationProcessorRegistry;
import xyz.avasilev.taskcomm.execution.api.HistoryWindow;
import xyz.avasilev.taskcomm.execution.api.Page;
import xyz.avasilev.taskcomm.execution.api.PageRequest;
import xyz.avasilev.taskcomm.rest.SqlLiterals;

/**
 * Чтение коммуникаций (docs/adr/0138-inspection-api.md): страницы по ключу, фильтры, окно и дело,
 * лукап по {@code request_id}, обмен попытки, реестр корреляции, сводка, осиротевшие системы.
 */
class DefaultCommunicationInspectionIT {

    private CommunicationTestDatabase db;

    private CommunicationCreator creator;

    private DefaultCommunicationInspection inspection;

    @BeforeEach
    void createSchema() {
        this.db = CommunicationTestDatabase.create();
        this.creator = this.db.creator();
        final CommunicationProcessorRegistry registry =
                new CommunicationProcessorRegistry(List.of(new CommunicationClaimIT.StockProcessor()));
        this.inspection = new DefaultCommunicationInspection(
                this.db.dataSource,
                this.db.codec,
                () -> registry,
                () -> {
                    throw new IllegalStateException("движок в этом тесте не нужен");
                },
                () -> null);
    }

    @Test
    void pagesCoverEveryRowOnceNewestFirst() {
        final List<UUID> created = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            created.add(this.outbound("stock", NewCommunication.Keys.NONE).id());
        }
        final List<UUID> seen = new ArrayList<>();
        Page<CommunicationSnapshot> page =
                this.inspection.list(CommunicationQuery.all(), this.window(), PageRequest.first(2));
        page.items().forEach(item -> seen.add(item.id()));
        while (page.next().isPresent()) {
            page = this.inspection.list(
                    CommunicationQuery.all(),
                    this.window(),
                    PageRequest.after(page.next().get(), 2));
            page.items().forEach(item -> seen.add(item.id()));
        }

        assertThat(seen).containsExactlyElementsOf(created.reversed());
    }

    @Test
    void filtersWindowAndOrphans() {
        final CreatedCommunication stock = this.outbound("stock", NewCommunication.Keys.NONE);
        this.outbound("gone", NewCommunication.Keys.NONE);
        this.inbound("req-1");
        final HistoryWindow past = HistoryWindow.between(
                this.db.currentMonth().minus(60, ChronoUnit.DAYS),
                this.db.currentMonth().minus(30, ChronoUnit.DAYS));

        assertThat(this.items(CommunicationQuery.all().withDirection(CommunicationSnapshot.Direction.OUTBOUND)))
                .hasSize(2);
        assertThat(this.items(CommunicationQuery.all().withSystem("gone")))
                .singleElement()
                .satisfies(gone -> assertThat(gone.orphan()).isTrue());
        assertThat(this.items(CommunicationQuery.all().withStatuses(Set.of(CommunicationStatus.FAILED))))
                .isEmpty();
        assertThat(this.inspection
                        .list(CommunicationQuery.all(), past, PageRequest.first(10))
                        .items())
                .isEmpty();
        assertThat(this.inspection
                        .list(CommunicationQuery.all().inExecution(stock.execution()), past, PageRequest.first(10))
                        .items())
                .extracting(CommunicationSnapshot::id)
                .containsExactly(stock.id());
        assertThat(this.inspection.find(stock.id(), this.window())).hasValueSatisfying(found -> {
            assertThat(found.externalSystem()).isEqualTo("stock");
            assertThat(found.requestPayload()).hasValue("{\"sku\": 1}");
            assertThat(found.orphan()).isFalse();
        });
    }

    @Test
    void requestIdFindsCommunicationAndAttemptShowsExchange() {
        final CreatedCommunication wanted = this.inbound("req-7");
        this.inbound("req-8");

        final List<CommunicationSnapshot> found =
                this.items(CommunicationQuery.all().withRequestId("req-7"));

        assertThat(found).extracting(CommunicationSnapshot::id).containsExactly(wanted.id());
        assertThat(this.inspection.attempts(found.getFirst())).singleElement().satisfies(attempt -> {
            assertThat(attempt.status()).isEmpty();
            assertThat(attempt.request()).hasValueSatisfying(request -> {
                assertThat(request.body()).isEqualTo("{\"sku\":1}");
                assertThat(request.id()).hasValue("req-7");
                assertThat(request.headers().asMap()).containsEntry("x-trace", List.of("t-1"));
            });
            assertThat(attempt.response()).isEmpty();
        });
    }

    @Test
    void correlationKeysOfExecution() {
        final CreatedCommunication anchor =
                this.outbound("stock", new NewCommunication.Keys(List.of(), List.of(CorrelationKey.of("corr-1"))));

        assertThat(this.inspection.correlationKeys(anchor.execution()))
                .singleElement()
                .satisfies(key -> {
                    assertThat(key.externalSystem()).isEqualTo("stock");
                    assertThat(key.correlationId()).isEqualTo("corr-1");
                    assertThat(key.communicationId()).isEqualTo(anchor.id());
                });
    }

    @Test
    void keysOfCommunicationAndSearchByThem() {
        final CreatedCommunication owner = this.outbound(
                "stock",
                new NewCommunication.Keys(
                        List.of(new DedupKey.WholeHistory("k-1"), new DedupKey.LastSucceeded("sku-1", "price-1")),
                        List.of(CorrelationKey.of("corr-1"))));
        final CreatedCommunication other =
                this.outbound("stock", new NewCommunication.Keys(List.of(new DedupKey.WholeHistory("k-2")), List.of()));
        final CommunicationSnapshot found =
                this.inspection.find(owner.id(), this.window()).orElseThrow();

        assertThat(this.inspection.dedupKeys(found))
                .extracting(DedupKeySnapshot::subject, DedupKeySnapshot::key)
                .containsExactly(tuple(null, "k-1"), tuple("sku-1", "price-1"));
        assertThat(this.inspection.correlationKeys(found))
                .extracting(CorrelationKeySnapshot::externalSystem, CorrelationKeySnapshot::correlationId)
                .containsExactly(tuple("stock", "corr-1"));
        final CommunicationQuery stock = CommunicationQuery.all()
                .withDirection(CommunicationSnapshot.Direction.OUTBOUND)
                .withSystem("stock");
        assertThat(this.items(stock.withDedupKey("k-1")))
                .extracting(CommunicationSnapshot::id)
                .containsExactly(owner.id());
        assertThat(this.items(CommunicationQuery.all().withDedupKey("k-2")))
                .extracting(CommunicationSnapshot::id)
                .containsExactly(other.id());
        assertThat(this.items(stock.withDedupKey("sku-1", "price-1")))
                .extracting(CommunicationSnapshot::id)
                .containsExactly(owner.id());
        assertThat(this.items(stock.withDedupKey("price-1"))).isEmpty();
        assertThat(this.items(CommunicationQuery.all().withCorrelationKey("stock", "corr-1")))
                .extracting(CommunicationSnapshot::id)
                .containsExactly(owner.id());
    }

    @Test
    void summaryCountsByDirectionSystemAndStatus() {
        this.outbound("stock", NewCommunication.Keys.NONE);
        this.outbound("stock", NewCommunication.Keys.NONE);
        this.outbound("gone", NewCommunication.Keys.NONE);

        assertThat(this.inspection.summary(HistoryWindow.everywhere()))
                .containsExactlyInAnyOrder(
                        new CommunicationSummary(
                                CommunicationSnapshot.Direction.OUTBOUND, "gone", CommunicationStatus.PENDING, 1, true),
                        new CommunicationSummary(
                                CommunicationSnapshot.Direction.OUTBOUND,
                                "stock",
                                CommunicationStatus.PENDING,
                                2,
                                false));
    }

    /**
     * SQL для разбора (docs/adr/0165-read-sql.md) с подставленными значениями, выполненный как есть, даёт
     * те же строки, что чтение: фильтры, ключи с пустым предметом, дело, курсор, попытки, корреляция.
     */
    @Test
    void readSqlWithLiteralsReturnsTheSameRows() {
        final CreatedCommunication owner = this.outbound(
                "stock",
                new NewCommunication.Keys(
                        List.of(new DedupKey.WholeHistory("k-1")), List.of(CorrelationKey.of("corr-1"))));
        this.outbound("stock", NewCommunication.Keys.NONE);
        final CreatedCommunication inbound = this.inbound("req-7");
        final List<CommunicationQuery> queries = List.of(
                CommunicationQuery.all()
                        .withDirection(CommunicationSnapshot.Direction.OUTBOUND)
                        .withSystem("stock")
                        .withStatuses(Set.of(CommunicationStatus.PENDING, CommunicationStatus.FAILED)),
                CommunicationQuery.all().withDedupKey("k-1"),
                CommunicationQuery.all().withCorrelationKey("stock", "corr-1"),
                CommunicationQuery.all().withRequestId("req-7"),
                CommunicationQuery.all().inExecution(owner.execution()));
        for (final CommunicationQuery query : queries) {
            assertThat(this.ids(this.inspection.listSql(query, this.window(), PageRequest.first(10))))
                    .isNotEmpty()
                    .containsExactlyElementsOf(this.items(query).stream()
                            .map(CommunicationSnapshot::id)
                            .toList());
        }
        final Page<CommunicationSnapshot> first =
                this.inspection.list(CommunicationQuery.all(), this.window(), PageRequest.first(1));
        final PageRequest second = PageRequest.after(first.next().orElseThrow(), 1);
        assertThat(this.ids(this.inspection.listSql(CommunicationQuery.all(), this.window(), second)))
                .containsExactlyElementsOf(
                        this.inspection.list(CommunicationQuery.all(), this.window(), second).items().stream()
                                .map(CommunicationSnapshot::id)
                                .toList());

        final CommunicationSnapshot received =
                this.inspection.find(inbound.id(), this.window()).orElseThrow();
        assertThat(this.ids(this.inspection.attemptsSql(received))).hasSize(1);
        assertThat(this.db
                        .jdbc
                        .sql(SqlLiterals.statement(this.inspection.correlationKeysSql(owner.execution())))
                        .query((rs, rowNum) -> rs.getString("correlation_id"))
                        .list())
                .containsExactly("corr-1");
    }

    private List<UUID> ids(final SqlQuery query) {
        return this.db
                .jdbc
                .sql(SqlLiterals.statement(query))
                .query((rs, rowNum) -> rs.getObject("id", UUID.class))
                .list();
    }

    private List<CommunicationSnapshot> items(final CommunicationQuery query) {
        return this.inspection.list(query, this.window(), PageRequest.first(10)).items();
    }

    private CreatedCommunication outbound(final String system, final NewCommunication.Keys keys) {
        return this.creator.create(new NewCommunication(
                Direction.OUTBOUND,
                system,
                "{\"sku\":1}",
                null,
                new NewCommunication.Scheduling(100, Duration.ofMinutes(1), null),
                new NewCommunication.Placement(null, null),
                NewCommunication.Start.OUTBOUND,
                keys));
    }

    private CreatedCommunication inbound(final String requestId) {
        return this.creator.create(new NewCommunication(
                Direction.INBOUND,
                "stock",
                "{\"sku\":1}",
                ContractShape.KAFKA,
                new NewCommunication.Scheduling(100, Duration.ofMinutes(1), null),
                new NewCommunication.Placement(null, null),
                new NewCommunication.Start(
                        ExchangeMessage.of("{\"sku\":1}")
                                .withId(requestId)
                                .withHeaders(CommunicationHeaders.empty().with("x-trace", "t-1")),
                        null,
                        null,
                        null),
                NewCommunication.Keys.NONE));
    }

    private HistoryWindow window() {
        return HistoryWindow.between(
                this.db.currentMonth(), this.db.currentMonth().plus(62, ChronoUnit.DAYS));
    }
}
