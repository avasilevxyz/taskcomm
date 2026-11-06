package xyz.avasilev.taskcomm.communication.internal.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import xyz.avasilev.taskcomm.TaskcommConfigurationException;
import xyz.avasilev.taskcomm.communication.api.Communication;
import xyz.avasilev.taskcomm.communication.api.CommunicationHeaders;
import xyz.avasilev.taskcomm.communication.api.processor.BodyDecoder;
import xyz.avasilev.taskcomm.communication.api.processor.CommunicationContext;
import xyz.avasilev.taskcomm.communication.api.processor.CommunicationProcessor;
import xyz.avasilev.taskcomm.communication.api.processor.CommunicationProcessorSettings;
import xyz.avasilev.taskcomm.communication.api.processor.KafkaCommunicationProcessor;
import xyz.avasilev.taskcomm.communication.api.processor.RawCommunicationProcessor;
import xyz.avasilev.taskcomm.communication.api.processor.RestCommunicationProcessor;
import xyz.avasilev.taskcomm.communication.api.processor.SoapCommunicationProcessor;
import xyz.avasilev.taskcomm.communication.internal.CommunicationPhase;
import xyz.avasilev.taskcomm.communication.internal.CommunicationTestSettings;
import xyz.avasilev.taskcomm.communication.internal.processor.CommunicationProcessorRegistry;

/**
 * Разрешение настроек коммуникаций по пяти уровням, отказные статусы по транспорту и реестр
 * процессоров: две системы одним процессором, SOAP без XML-маппера (docs/adr/0135-communication-engine.md).
 */
class CommunicationSettingsTest {

    private static final AttemptSettings GLOBAL =
            CommunicationTestSettings.global(5, Duration.ofSeconds(10), Duration.ofMinutes(5));

    private static final PollerLayout POLLERS = CommunicationTestSettings.POLLERS;

    @Test
    void yamlBeatsMethodAnnotationWhichBeatsClassAnnotation() {
        final CommunicationSettings settings = resolve(
                Map.of(
                        "rest",
                        new SystemOverrides(
                                new SettingsOverride(
                                        7, LeaseOverride.NONE, new RetryOverride(null, null, null, null, 0.5), null),
                                Map.of(
                                        "handle-response",
                                        new SettingsOverride(
                                                null,
                                                LeaseOverride.NONE,
                                                new RetryOverride(9, null, null, null, null),
                                                null)))),
                new RestProcessor());

        final AttemptSettings process = settings.attempt("rest", CommunicationPhase.PROCESS);
        assertThat(process.retry().maxAttempts()).isEqualTo(3);
        assertThat(process.retry().jitter()).isEqualTo(0.5);
        assertThat(process.lease().maxExecutionTime()).isEqualTo(Duration.ofMinutes(2));
        assertThat(settings.attempt("rest", CommunicationPhase.HANDLE_RESPONSE)
                        .retry()
                        .maxAttempts())
                .isEqualTo(9);
        assertThat(settings.priority("rest")).isEqualTo(7);
        assertThat(settings.attempt("unknown", CommunicationPhase.PROCESS)).isEqualTo(GLOBAL);
    }

    @Test
    void failureStatusesDependOnTransportUnlessSet() {
        final CommunicationSettings settings =
                resolve(Map.of(), new RestProcessor(), new KafkaProcessor(), new RawProcessor());

        assertThat(settings.failureStatuses("rest").values()).containsExactly("5xx", "429");
        assertThat(settings.failureStatuses("kafka").values()).isEmpty();
        assertThat(settings.failureStatuses("raw").values()).containsExactly("418");
        assertThat(settings.failureStatuses("rest").failed(503)).isTrue();
        assertThat(settings.failureStatuses("rest").failed(429)).isTrue();
        assertThat(settings.failureStatuses("rest").failed(404)).isFalse();
    }

    @Test
    void systemSettingsOnPhaseOrMethodFailAtStartup() {
        assertThatThrownBy(() -> resolve(
                        Map.of(
                                "rest",
                                new SystemOverrides(
                                        SettingsOverride.NONE,
                                        Map.of(
                                                "process",
                                                new SettingsOverride(
                                                        null,
                                                        LeaseOverride.NONE,
                                                        RetryOverride.NONE,
                                                        List.of("5xx"))))),
                        new RestProcessor()))
                .isInstanceOf(TaskcommConfigurationException.class)
                .hasMessageContaining("на фазе запрещены");
        assertThatThrownBy(() -> resolve(Map.of(), new PriorityOnMethodProcessor()))
                .isInstanceOf(TaskcommConfigurationException.class)
                .hasMessageContaining("Атрибут priority на методе запрещён");
        assertThatThrownBy(() -> new FailureStatuses(List.of("5x"))).isInstanceOf(TaskcommConfigurationException.class);
    }

    @Test
    void registryRejectsTwoProcessorsOfOneSystemAndSoapWithoutXml() {
        assertThatThrownBy(() -> new CommunicationProcessorRegistry(List.of(new RestProcessor(), new RestProcessor())))
                .isInstanceOf(TaskcommConfigurationException.class)
                .hasMessageContaining("объявлена двумя процессорами");
        assertThatThrownBy(() -> new CommunicationProcessorRegistry(List.of(new SoapProcessor()), false))
                .isInstanceOf(TaskcommConfigurationException.class)
                .hasMessageContaining("jackson-dataformat-xml");
        assertThat(new CommunicationProcessorRegistry(List.of(new DecodingSoapProcessor()), false).systems())
                .containsExactly("soap");
    }

    private static CommunicationSettings resolve(
            final Map<String, SystemOverrides> yaml, final CommunicationProcessor<?, ?, ?>... processors) {
        return CommunicationSettings.resolve(
                GLOBAL, yaml, new CommunicationProcessorRegistry(List.of(processors), true), POLLERS, Timing.DEFAULT);
    }

    /** REST: класс задаёт три попытки, метод — предел обработки. */
    @CommunicationProcessorSettings(maxAttempts = 3, priority = 20)
    static class RestProcessor implements RestCommunicationProcessor<String, Void, Void> {

        @Override
        public String externalSystem() {
            return "rest";
        }

        @Override
        @CommunicationProcessorSettings(maxExecutionTime = "2m")
        public Void process(final Communication<String> communication, final CommunicationContext context) {
            return null;
        }
    }

    /** Kafka без настроек. */
    static class KafkaProcessor implements KafkaCommunicationProcessor<String, Void, Void> {

        @Override
        public String externalSystem() {
            return "kafka";
        }

        @Override
        public Void process(final Communication<String> communication, final CommunicationContext context) {
            return null;
        }
    }

    /** RAW со своими отказными статусами. */
    @CommunicationProcessorSettings(failureStatuses = "418")
    static class RawProcessor implements RawCommunicationProcessor<String, Void, Void> {

        @Override
        public String externalSystem() {
            return "raw";
        }

        @Override
        public String decodeRequest(final String body, final CommunicationHeaders headers) {
            return body;
        }

        @Override
        public Void decodeResponse(final String body, final CommunicationHeaders headers) {
            return null;
        }

        @Override
        public Void process(final Communication<String> communication, final CommunicationContext context) {
            return null;
        }
    }

    /** Приоритет на методе — падение. */
    static class PriorityOnMethodProcessor implements KafkaCommunicationProcessor<String, Void, Void> {

        @Override
        public String externalSystem() {
            return "bad";
        }

        @Override
        @CommunicationProcessorSettings(priority = 5)
        public Void process(final Communication<String> communication, final CommunicationContext context) {
            return null;
        }
    }

    /** SOAP с разбором по умолчанию. */
    static class SoapProcessor implements SoapCommunicationProcessor<String, Void, Void> {

        @Override
        public String externalSystem() {
            return "soap";
        }

        @Override
        public Void process(final Communication<String> communication, final CommunicationContext context) {
            return null;
        }
    }

    /** SOAP со своим разбором — XML-маппер не нужен. */
    static class DecodingSoapProcessor extends SoapProcessor {

        @Override
        public String decodeRequest(
                final String body, final CommunicationHeaders headers, final BodyDecoder<String> xml) {
            return body;
        }
    }
}
