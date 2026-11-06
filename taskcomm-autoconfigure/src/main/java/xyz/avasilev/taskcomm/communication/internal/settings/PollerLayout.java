package xyz.avasilev.taskcomm.communication.internal.settings;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import xyz.avasilev.taskcomm.TaskcommConfigurationException;

/**
 * Основной поллер коммуникаций и выделенные (docs/adr/0063-claim-ordering-and-pollers.md): какой
 * поллер отвечает за внешнюю систему. Основной систем выделенных не берёт. Система, названная в двух
 * выделенных поллерах, — падение при старте.
 *
 * @param main основной поллер
 * @param dedicated выделенные поллеры
 */
public record PollerLayout(PollerSettings main, List<PollerSettings> dedicated) {

    /**
     * Проверяет раскладку и копирует список.
     *
     * @throws TaskcommConfigurationException если система названа в двух поллерах или основной поллер
     *     оказался в списке выделенных
     */
    public PollerLayout {
        Objects.requireNonNull(main, "main");
        dedicated = List.copyOf(dedicated);
        if (!PollerSettings.MAIN.equals(main.name())) {
            throw new TaskcommConfigurationException(
                    "Основной поллер коммуникаций обязан называться " + PollerSettings.MAIN);
        }
        final Map<String, String> owners = new HashMap<>();
        for (final PollerSettings poller : dedicated) {
            if (PollerSettings.MAIN.equals(poller.name())) {
                throw new TaskcommConfigurationException("Имя " + PollerSettings.MAIN
                        + " занято основным поллером коммуникаций, выделенному оно не годится.");
            }
            for (final String system : poller.systems()) {
                final String previous = owners.putIfAbsent(system, poller.name());
                if (previous != null) {
                    throw new TaskcommConfigurationException("Внешняя система '" + system + "' названа в двух "
                            + "поллерах: '" + previous + "' и '" + poller.name() + "'. Систему обслуживает ровно "
                            + "один поллер.");
                }
            }
        }
    }

    /**
     * Поллер, отвечающий за систему.
     *
     * @param system внешняя система
     * @return выделенный поллер системы, иначе основной
     */
    public PollerSettings pollerOf(final String system) {
        return this.dedicated.stream()
                .filter(poller -> poller.systems().contains(system))
                .findFirst()
                .orElse(this.main);
    }

    /**
     * Шаг дедлайна, по которому считается {@code claim_order_at} коммуникаций системы.
     *
     * @param system внешняя система
     * @return шаг дедлайна
     */
    public Duration deadlineStep(final String system) {
        return this.pollerOf(system).deadlineStep();
    }

    /**
     * Какие из известных систем захватывает поллер: выделенный — свои, основной — все прочие.
     *
     * @param poller поллер из этой раскладки
     * @param known системы, для которых есть процессор
     * @return системы, которые поллер вправе захватывать
     */
    public Set<String> claimableSystems(final PollerSettings poller, final Set<String> known) {
        final Set<String> result = new HashSet<>(known);
        if (PollerSettings.MAIN.equals(poller.name())) {
            this.dedicated.forEach(other -> result.removeAll(other.systems()));
        } else {
            result.retainAll(poller.systems());
        }
        return Set.copyOf(result);
    }
}
