package xyz.avasilev.taskcomm.communication.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import xyz.avasilev.taskcomm.communication.api.CommunicationSendOptions;
import xyz.avasilev.taskcomm.communication.api.ExchangeMessage;
import xyz.avasilev.taskcomm.execution.api.ExecutionRef;
import xyz.avasilev.taskcomm.execution.api.Origin;
import xyz.avasilev.taskcomm.execution.internal.CurrentExecution;

/**
 * Коммуникация к созданию.
 *
 * @param direction направление
 * @param system внешняя система
 * @param payload доменная форма запроса в JSON или {@code null} у неразобранного тела
 * @param shape форма контракта — у входящей при создании, у исходящей {@code null} до первого захвата
 * @param scheduling приоритет и готовность
 * @param placement дело и создатель
 * @param start как начинается первая попытка
 */
record NewCommunication(
        Direction direction,
        String system,
        String payload,
        ContractShape shape,
        Scheduling scheduling,
        Placement placement,
        Start start) {

    /** Окончание сообщения методов, отложенных до этапа 5c. */
    static final String NOT_YET =
            " появится вместе с корреляцией и дедупликацией (этап 5c, " + "docs/adr/0135-communication-engine.md)";

    /**
     * Отказ ключам дедупликации и корреляции до этапа 5c — до записи чего-либо.
     *
     * @param present заданы ли ключи
     * @throws UnsupportedOperationException если заданы
     */
    static void requireNoKeys(final boolean present) {
        if (present) {
            throw new UnsupportedOperationException("Ключи дедупликации и корреляции при создании" + NOT_YET);
        }
    }

    /**
     * Приоритет и готовность.
     *
     * @param priority приоритет
     * @param deadlineStep шаг дедлайна поллера системы
     * @param notBefore «не раньше» или {@code null}
     */
    record Scheduling(int priority, Duration deadlineStep, Instant notBefore) {}

    /**
     * Дело и создатель.
     *
     * @param execution дело или {@code null} — завести новое
     * @param origin создатель или {@code null}
     */
    record Placement(ExecutionRef execution, Origin origin) {

        /**
         * Дело и создатель по правилам привязки (docs/rules/execution.md): новое дело по просьбе; явное
         * дело, а создатель — только если поток обрабатывает сущность этого же дела; иначе дело и создатель
         * из привязки потока; без привязки — новое дело.
         *
         * @param options опции создания
         * @return дело и создатель
         */
        static Placement of(final CommunicationSendOptions options) {
            final Optional<CurrentExecution.Binding> bound = CurrentExecution.get();
            if (options.newExecution()) {
                return new Placement(null, null);
            }
            if (options.executionRef().isPresent()) {
                final ExecutionRef explicit = options.executionRef().get();
                final Origin creator = bound.filter(
                                binding -> binding.execution().equals(explicit))
                        .map(CurrentExecution.Binding::creator)
                        .orElse(null);
                return new Placement(explicit, creator);
            }
            return bound.map(binding -> new Placement(binding.execution(), binding.creator()))
                    .orElseGet(() -> new Placement(null, null));
        }
    }

    /**
     * Как начинается первая попытка.
     *
     * @param request что прислали — у входящей; у исходящей {@code null}
     * @param failure причина неразобранного тела — коммуникация сразу {@code FAILED}; иначе {@code null}
     * @param lease аренда синхронного приёма — коммуникация сразу {@code RUNNING} под этим экземпляром;
     *     иначе {@code null}
     * @param lockedBy имя экземпляра при синхронном приёме
     */
    record Start(ExchangeMessage request, String failure, AttemptLease lease, String lockedBy) {

        /** Исходящая: попытка открыта и не захвачена. */
        static final Start OUTBOUND = new Start(null, null, null, null);
    }
}
