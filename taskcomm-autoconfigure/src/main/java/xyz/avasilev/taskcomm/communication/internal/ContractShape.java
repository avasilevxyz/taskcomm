package xyz.avasilev.taskcomm.communication.internal;

import xyz.avasilev.taskcomm.communication.api.CommunicationProcessor;
import xyz.avasilev.taskcomm.communication.api.KafkaCommunicationProcessor;
import xyz.avasilev.taskcomm.communication.api.RawCommunicationProcessor;
import xyz.avasilev.taskcomm.communication.api.RestCommunicationProcessor;
import xyz.avasilev.taskcomm.communication.api.SoapCommunicationProcessor;

/**
 * Форма транспортного контракта — колонка {@code contract_shape}
 * (docs/adr/0052-communication-transport-contracts.md). Значение читается как имя интерфейса.
 */
public enum ContractShape {

    /** {@link RestCommunicationProcessor}. */
    REST,

    /** {@link SoapCommunicationProcessor}. */
    SOAP,

    /** {@link KafkaCommunicationProcessor}. */
    KAFKA,

    /** {@link RawCommunicationProcessor}. */
    RAW;

    /**
     * Форма контракта процессора. Разбор запечатанной иерархии без ветки {@code default}: пятый
     * транспорт сломает компиляцию здесь.
     *
     * @param processor процессор
     * @return форма его контракта
     */
    static ContractShape of(final CommunicationProcessor<?, ?, ?> processor) {
        return switch (processor) {
            case RestCommunicationProcessor<?, ?, ?> _ -> REST;
            case SoapCommunicationProcessor<?, ?, ?> _ -> SOAP;
            case KafkaCommunicationProcessor<?, ?, ?> _ -> KAFKA;
            case RawCommunicationProcessor<?, ?, ?> _ -> RAW;
        };
    }

    /**
     * Есть ли у ответа этого транспорта код — REST и SOAP.
     *
     * @return {@code true} у HTTP-транспортов
     */
    boolean http() {
        return this == REST || this == SOAP;
    }
}
