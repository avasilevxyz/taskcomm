package xyz.avasilev.taskcomm.communication.internal.reception;

import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.transaction.PlatformTransactionManager;
import xyz.avasilev.taskcomm.communication.internal.CommunicationCodec;
import xyz.avasilev.taskcomm.communication.internal.creation.CommunicationCreator;
import xyz.avasilev.taskcomm.communication.internal.engine.CommunicationEngine;
import xyz.avasilev.taskcomm.communication.internal.inspection.CommunicationReader;
import xyz.avasilev.taskcomm.communication.internal.settings.CommunicationSettings;
import xyz.avasilev.taskcomm.execution.internal.UuidV7;

/**
 * Синхронный запуск коммуникаций (docs/adr/0167-communication-run-mode.md) — приём и отправка поверх
 * общих попыток в потоке вызывающего.
 */
public final class SyncRuns {

    private final SyncReception reception;

    private final SyncSending sending;

    /**
     * Собирает синхронный запуск.
     *
     * @param settings итоговые настройки — лениво
     * @param engine движок — воркер, heartbeat и защита отправки; лениво
     * @param creator создание коммуникаций
     * @param codec кодек доменных форм
     * @param reader чтение коммуникации для итогов
     * @param ids генератор идентификаторов
     * @param dataSource {@code DataSource} стартера
     * @param transactionManager менеджер транзакций стартера
     */
    public SyncRuns(
            final Supplier<CommunicationSettings> settings,
            final Supplier<CommunicationEngine> engine,
            final CommunicationCreator creator,
            final CommunicationCodec codec,
            final CommunicationReader reader,
            final UuidV7 ids,
            final DataSource dataSource,
            final PlatformTransactionManager transactionManager) {
        final SyncAttempts attempts = new SyncAttempts(settings, engine, ids, dataSource, transactionManager);
        this.reception = new SyncReception(attempts, creator, reader);
        this.sending = new SyncSending(attempts, settings, creator, reader, codec, dataSource);
    }

    SyncReception reception() {
        return this.reception;
    }

    SyncSending sending() {
        return this.sending;
    }
}
