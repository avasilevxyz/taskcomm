package xyz.avasilev.taskcomm.task.api.inspection;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Снимок попытки задачи, группы или подзадачи — строка журнала попыток с результатом
 * (docs/adr/0044-attempt-log.md, docs/adr/0138-inspection-api.md).
 */
public interface TaskAttemptSnapshot {

    /**
     * Идентификатор попытки.
     *
     * @return идентификатор
     */
    UUID id();

    /**
     * Фаза попытки: {@code PROCESS}, {@code EXPAND}, {@code COLLECT}.
     *
     * @return фаза
     */
    String phase();

    /**
     * Исход: {@code SUCCEEDED}, {@code FAILED}, {@code SKIPPED}, {@code LEASE_LOST}.
     *
     * @return исход или пусто у открытой попытки
     */
    Optional<String> status();

    /**
     * Причина исхода.
     *
     * @return причина или пусто
     */
    Optional<String> statusDescription();

    /**
     * Начало попытки — захват.
     *
     * @return время начала
     */
    Instant startedAt();

    /**
     * Конец попытки.
     *
     * @return время конца или пусто у открытой
     */
    Optional<Instant> finishedAt();

    /**
     * Экземпляр, захвативший попытку.
     *
     * @return имя экземпляра
     */
    String lockedBy();

    /**
     * Сколько раз продлевалась аренда.
     *
     * @return число продлений
     */
    int leaseRenewals();

    /**
     * Результат попытки сырым JSON (docs/adr/0058-task-result-storage.md).
     *
     * @return результат или пусто — не хранится, удалён или попытка без результата
     */
    Optional<String> result();
}
