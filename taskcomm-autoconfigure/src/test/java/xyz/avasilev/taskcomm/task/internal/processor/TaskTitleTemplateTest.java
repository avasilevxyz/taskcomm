package xyz.avasilev.taskcomm.task.internal.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import xyz.avasilev.taskcomm.TaskcommConfigurationException;
import xyz.avasilev.taskcomm.task.api.Task;
import xyz.avasilev.taskcomm.task.api.TaskBatch;
import xyz.avasilev.taskcomm.task.api.TaskUnit;
import xyz.avasilev.taskcomm.task.api.processor.BatchTaskProcessor;
import xyz.avasilev.taskcomm.task.api.processor.CollectedBatch;
import xyz.avasilev.taskcomm.task.api.processor.CollectedUnit;
import xyz.avasilev.taskcomm.task.api.processor.Emitter;
import xyz.avasilev.taskcomm.task.api.processor.FlatTaskProcessor;
import xyz.avasilev.taskcomm.task.api.processor.ProcessingContext;
import xyz.avasilev.taskcomm.task.api.processor.SearchableField;

class TaskTitleTemplateTest {

    @Test
    void placeholdersInOrderOfAppearance() {
        assertThat(TaskTitleTemplate.placeholders("Выгрузка {catalog} от {supplier}, снова {catalog}"))
                .containsExactly("catalog", "supplier");
        assertThat(TaskTitleTemplate.placeholders(null)).isEmpty();
    }

    @Test
    void placeholderMustBeTaskLevelSearchField() {
        TaskTitleTemplate.check(new Export("Выгрузка каталога {catalog}"));
        TaskTitleTemplate.check(new Export(null));

        assertThatThrownBy(() -> TaskTitleTemplate.check(new Export("Выгрузка {season}")))
                .isInstanceOf(TaskcommConfigurationException.class)
                .hasMessageContaining("{season}")
                .hasMessageContaining("catalog");
    }

    @Test
    void batchAndUnitTemplatesUseTheirOwnLevelFields() {
        TaskTitleTemplate.check(new Groups("Поставщик {supplier}", "Позиция {sku}"));

        assertThatThrownBy(() -> TaskTitleTemplate.check(new Groups("Поставщик {sku}", null)))
                .isInstanceOf(TaskcommConfigurationException.class)
                .hasMessageContaining("группы")
                .hasMessageContaining("{sku}");
        assertThatThrownBy(() -> TaskTitleTemplate.check(new Groups(null, "Позиция {supplier}")))
                .isInstanceOf(TaskcommConfigurationException.class)
                .hasMessageContaining("подзадачи");
    }

    @Test
    void registryRejectsUnknownPlaceholderAtStart() {
        assertThatThrownBy(() -> new TaskProcessorRegistry(List.of(new Export("Выгрузка {season}"))))
                .isInstanceOf(TaskcommConfigurationException.class);
    }

    /** Процессор с группами и шаблонами групп и подзадач. */
    static final class Groups implements BatchTaskProcessor<Payload, Payload, Payload, Void, Void, Void> {

        private final String batch;

        private final String unit;

        Groups(final String batch, final String unit) {
            this.batch = batch;
            this.unit = unit;
        }

        @Override
        public String taskType() {
            return "groups";
        }

        @Override
        public String batchTitle() {
            return this.batch;
        }

        @Override
        public String unitTitle() {
            return this.unit;
        }

        @Override
        public List<SearchableField> searchableBatchFields() {
            return List.of(SearchableField.number("supplier", "$.supplier"));
        }

        @Override
        public List<SearchableField> searchableUnitFields() {
            return List.of(SearchableField.string("sku", "$.sku"));
        }

        @Override
        public void expand(final Task<Payload> task, final Emitter<Payload> batches, final ProcessingContext context) {
            // не вызывается
        }

        @Override
        public void expandBatch(
                final TaskBatch<Payload> batch, final Emitter<Payload> units, final ProcessingContext context) {
            // не вызывается
        }

        @Override
        public Void process(final TaskUnit<Payload> unit, final ProcessingContext context) {
            return null;
        }

        @Override
        public Void collectBatch(
                final TaskBatch<Payload> batch,
                final Stream<CollectedUnit<Payload, Void>> units,
                final ProcessingContext context) {
            return null;
        }

        @Override
        public Void collect(
                final Task<Payload> task,
                final Stream<CollectedBatch<Payload, Void>> batches,
                final ProcessingContext context) {
            return null;
        }
    }

    /**
     * Нагрузка выгрузки.
     *
     * @param catalog каталог
     */
    record Payload(String catalog) {}

    /** Процессор с шаблоном названия. */
    static final class Export implements FlatTaskProcessor<Payload, Void> {

        private final String template;

        Export(final String template) {
            this.template = template;
        }

        @Override
        public String taskType() {
            return "export";
        }

        @Override
        public String taskTitle() {
            return this.template;
        }

        @Override
        public List<SearchableField> searchableTaskFields() {
            return List.of(SearchableField.string("catalog", "$.catalog"));
        }

        @Override
        public Void process(final Task<Payload> task, final ProcessingContext context) {
            return null;
        }
    }
}
