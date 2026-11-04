package xyz.avasilev.taskcomm.communication.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import xyz.avasilev.taskcomm.execution.api.ExecutionRef;

/**
 * Значения-запросы, опции, заголовки и сообщения обмена неизменяемы: каждый метод настройки
 * возвращает новое значение и не трогает прежнее.
 */
class CommunicationValuesTest {

    @Test
    void inboundRequestAccumulatesKeysWithoutChangingOriginal() {
        final InboundRequest plain = InboundRequest.of("{}");
        final Instant at = Instant.parse("2026-11-05T10:00:00Z");

        final InboundRequest full = plain.withRequestId("m-1")
                .withRequestAt(at)
                .withDedupKey("m-1")
                .withDedupKey("sku-1", "hash")
                .withCorrelationId("ref")
                .withCorrelationId("bank", "ref-2");

        assertThat(plain.dedupKeys()).isEmpty();
        assertThat(plain.requestId()).isEmpty();
        assertThat(full.requestId()).contains("m-1");
        assertThat(full.requestAt()).contains(at);
        assertThat(full.dedupKeys())
                .containsExactly(new DedupKey.WholeHistory("m-1"), new DedupKey.LastSucceeded("sku-1", "hash"));
        assertThat(full.correlationKeys())
                .extracting(CorrelationKey::correlationId)
                .containsExactly("ref", "ref-2");
        assertThat(full.correlationKeys())
                .extracting(key -> key.externalSystem().orElse("-"))
                .containsExactly("-", "bank");
    }

    @Test
    void sendOptionsKeepExecutionChoiceExclusiveAndCheckPriority() {
        final ExecutionRef execution = new ExecutionRef(UUID.randomUUID(), Instant.now());

        final CommunicationSendOptions inExecution =
                CommunicationSendOptions.defaults().inExecution(execution).dedupKey("k");
        final CommunicationSendOptions detached = inExecution.inNewExecution();

        assertThat(inExecution.executionRef()).contains(execution);
        assertThat(detached.executionRef()).isEmpty();
        assertThat(detached.newExecution()).isTrue();
        assertThat(detached.dedupKeys()).containsExactly(new DedupKey.WholeHistory("k"));
        assertThat(CommunicationSendOptions.defaults().priority()).isEmpty();
        assertThatThrownBy(() -> CommunicationSendOptions.defaults().priority(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void headersLookUpNameIgnoringCaseAndKeepWhatWasGiven() {
        final CommunicationHeaders headers = CommunicationHeaders.of(
                        Map.of("Content-Type", List.of("application/json")))
                .with("X-Trace", "a")
                .with("x-trace", "b");

        assertThat(headers.first("content-type")).contains("application/json");
        assertThat(headers.values("X-TRACE")).containsExactly("a", "b");
        assertThat(headers.asMap()).containsOnlyKeys("Content-Type", "X-Trace", "x-trace");
        assertThat(CommunicationHeaders.empty().values("X")).isEmpty();
    }

    @Test
    void exchangeMessageStatusIsOptional() {
        final ExchangeMessage request = ExchangeMessage.of("{}").withId("r-1");
        final ExchangeMessage response = request.withStatus(503);

        assertThat(request.status()).isEmpty();
        assertThat(response.status()).hasValue(503);
        assertThat(response.id()).contains("r-1");
    }

    @Test
    void callbackRequestRequiresCorrelationId() {
        assertThatThrownBy(() -> CallbackRequest.of(null, "{}")).isInstanceOf(NullPointerException.class);
        assertThat(CallbackRequest.of("ref", "{}").withRequestId("c-1").requestId())
                .contains("c-1");
    }
}
