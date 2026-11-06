package xyz.avasilev.taskcomm.task.internal.inspection;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import xyz.avasilev.taskcomm.execution.api.ExecutionRef;
import xyz.avasilev.taskcomm.execution.api.Origin;
import xyz.avasilev.taskcomm.execution.api.OriginRelation;
import xyz.avasilev.taskcomm.execution.api.OriginType;
import xyz.avasilev.taskcomm.task.api.inspection.TaskAttemptSnapshot;
import xyz.avasilev.taskcomm.task.api.inspection.TaskSnapshot;

/** Снимки для {@link xyz.avasilev.taskcomm.task.api.inspection.TaskInspection} из строк выборки. */
final class TaskSnapshots {

    private TaskSnapshots() {}

    /**
     * Снимок сущности из строки с колонками выборки уровня.
     *
     * @param rs строка
     * @param level уровень
     * @param orphan у типа нет процессора
     * @return снимок
     * @throws SQLException ошибка чтения
     */
    static TaskSnapshot entity(final ResultSet rs, final TaskSnapshot.Level level, final boolean orphan)
            throws SQLException {
        final String originType = rs.getString("origin_type");
        return new Entity(
                level,
                new Identity(
                        rs.getObject("id", UUID.class),
                        new ExecutionRef(rs.getObject("execution_id", UUID.class), instant(rs, "execution_created_at")),
                        rs.getString("task_type")),
                new Parents(rs.getObject("task_id", UUID.class), rs.getObject("batch_id", UUID.class)),
                new State(
                        rs.getString("status"),
                        rs.getString("status_description"),
                        rs.getString("phase"),
                        rs.getInt("priority")),
                new Times(instant(rs, "created_at"), instant(rs, "updated_at")),
                originType == null
                        ? null
                        : new Origin(
                                OriginType.valueOf(originType),
                                rs.getObject("origin_id", UUID.class),
                                rs.getObject("origin_attempt_id", UUID.class),
                                OriginRelation.valueOf(rs.getString("origin_relation"))),
                rs.getString("payload"),
                orphan);
    }

    /**
     * Снимок попытки.
     *
     * @param rs строка попытки с результатом
     * @return снимок
     * @throws SQLException ошибка чтения
     */
    static TaskAttemptSnapshot attempt(final ResultSet rs) throws SQLException {
        final OffsetDateTime finished = rs.getObject("finished_at", OffsetDateTime.class);
        return new Attempt(
                rs.getObject("id", UUID.class),
                rs.getString("phase"),
                rs.getString("status"),
                rs.getString("status_description"),
                new Times(instant(rs, "started_at"), finished == null ? null : finished.toInstant()),
                rs.getString("locked_by"),
                rs.getInt("lease_renewals"),
                rs.getString("result"));
    }

    private static Instant instant(final ResultSet rs, final String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }

    /**
     * Идентичность сущности.
     *
     * @param id идентификатор
     * @param executionRef дело
     * @param taskType тип
     */
    private record Identity(UUID id, ExecutionRef executionRef, String taskType) {}

    /**
     * Родители.
     *
     * @param taskId задача или {@code null}
     * @param batchId группа или {@code null}
     */
    private record Parents(UUID taskId, UUID batchId) {}

    /**
     * Состояние.
     *
     * @param status статус
     * @param description причина или {@code null}
     * @param phase фаза или {@code null}
     * @param priority приоритет
     */
    private record State(String status, String description, String phase, int priority) {}

    /**
     * Пара моментов.
     *
     * @param first создание или начало
     * @param second изменение или конец; бывает {@code null}
     */
    private record Times(Instant first, Instant second) {}

    /**
     * Снимок сущности.
     *
     * @param level уровень
     * @param identity идентификатор, дело и тип
     * @param parents родители
     * @param state состояние
     * @param times создание и изменение
     * @param originValue создатель или {@code null}
     * @param payload нагрузка JSON
     * @param orphan у типа нет процессора
     */
    private record Entity(
            TaskSnapshot.Level level,
            Identity identity,
            Parents parents,
            State state,
            Times times,
            Origin originValue,
            String payload,
            boolean orphan)
            implements TaskSnapshot {

        @Override
        public UUID id() {
            return this.identity.id();
        }

        @Override
        public ExecutionRef executionRef() {
            return this.identity.executionRef();
        }

        @Override
        public String taskType() {
            return this.identity.taskType();
        }

        @Override
        public Optional<UUID> taskId() {
            return Optional.ofNullable(this.parents.taskId());
        }

        @Override
        public Optional<UUID> batchId() {
            return Optional.ofNullable(this.parents.batchId());
        }

        @Override
        public String status() {
            return this.state.status();
        }

        @Override
        public Optional<String> statusDescription() {
            return Optional.ofNullable(this.state.description());
        }

        @Override
        public Optional<String> phase() {
            return Optional.ofNullable(this.state.phase());
        }

        @Override
        public int priority() {
            return this.state.priority();
        }

        @Override
        public Instant createdAt() {
            return this.times.first();
        }

        @Override
        public Instant updatedAt() {
            return this.times.second();
        }

        @Override
        public Optional<Origin> origin() {
            return Optional.ofNullable(this.originValue);
        }
    }

    /**
     * Снимок попытки.
     *
     * @param id идентификатор
     * @param phase фаза
     * @param statusValue исход или {@code null}
     * @param description причина или {@code null}
     * @param times начало и конец
     * @param lockedBy экземпляр
     * @param leaseRenewals продления
     * @param resultValue результат JSON или {@code null}
     */
    private record Attempt(
            UUID id,
            String phase,
            String statusValue,
            String description,
            Times times,
            String lockedBy,
            int leaseRenewals,
            String resultValue)
            implements TaskAttemptSnapshot {

        @Override
        public Optional<String> status() {
            return Optional.ofNullable(this.statusValue);
        }

        @Override
        public Optional<String> statusDescription() {
            return Optional.ofNullable(this.description);
        }

        @Override
        public Instant startedAt() {
            return this.times.first();
        }

        @Override
        public Optional<Instant> finishedAt() {
            return Optional.ofNullable(this.times.second());
        }

        @Override
        public Optional<String> result() {
            return Optional.ofNullable(this.resultValue);
        }
    }
}
