package xyz.avasilev.taskcomm.communication.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Заголовки сообщения — нейтральный тип поверх {@code Map<String, List<String>>}
 * (docs/adr/0052-communication-transport-contracts.md). Одинаков для HTTP и брокеров: в контрактах
 * процессоров не появляются ни {@code HttpHeaders}, ни типы {@code kafka-clients} — тип в сигнатуре
 * процессора необратим, потому что процессор не удаляется никогда.
 *
 * <p>Неизменяемое значение; {@link #with(String, String)} возвращает новое. Имена хранятся как
 * переданы и в том же порядке, а поиск по имени не различает регистр — как в HTTP.
 */
public final class CommunicationHeaders {

    private static final CommunicationHeaders EMPTY = new CommunicationHeaders(Map.of());

    private final Map<String, List<String>> values;

    private CommunicationHeaders(final Map<String, List<String>> values) {
        this.values = values;
    }

    /**
     * Заголовков нет.
     *
     * @return пустые заголовки
     */
    public static CommunicationHeaders empty() {
        return EMPTY;
    }

    /**
     * Копия заголовков из отображения «имя — значения».
     *
     * @param values заголовки; копируются, порядок сохраняется
     * @return заголовки
     * @throws NullPointerException если отображение, имя или значение пусты
     */
    public static CommunicationHeaders of(final Map<String, ? extends List<String>> values) {
        final Map<String, List<String>> copy = new LinkedHashMap<>();
        values.forEach((name, list) -> copy.put(Objects.requireNonNull(name, "name"), List.copyOf(list)));
        return new CommunicationHeaders(Collections.unmodifiableMap(copy));
    }

    /**
     * Добавляет значение заголовка; прежние значения с тем же именем сохраняются.
     *
     * @param name имя заголовка
     * @param value значение
     * @return новые заголовки
     */
    public CommunicationHeaders with(final String name, final String value) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");
        final Map<String, List<String>> copy = new LinkedHashMap<>(this.values);
        final List<String> list = new ArrayList<>(copy.getOrDefault(name, List.of()));
        list.add(value);
        copy.put(name, List.copyOf(list));
        return new CommunicationHeaders(Collections.unmodifiableMap(copy));
    }

    /**
     * Все значения заголовка, без учёта регистра имени.
     *
     * @param name имя заголовка
     * @return значения в порядке добавления; пусто, если заголовка нет
     */
    public List<String> values(final String name) {
        final List<String> found = new ArrayList<>();
        this.values.forEach((key, list) -> {
            if (key.equalsIgnoreCase(name)) {
                found.addAll(list);
            }
        });
        return List.copyOf(found);
    }

    /**
     * Первое значение заголовка, без учёта регистра имени.
     *
     * @param name имя заголовка
     * @return значение или пусто
     */
    public Optional<String> first(final String name) {
        return this.values(name).stream().findFirst();
    }

    /**
     * Заголовки как отображение — в том виде, в каком они ложатся в {@code jsonb}.
     *
     * @return неизменяемое отображение «имя — значения»
     */
    public Map<String, List<String>> asMap() {
        return this.values;
    }

    @Override
    public boolean equals(final Object other) {
        return other instanceof final CommunicationHeaders headers && this.values.equals(headers.values);
    }

    @Override
    public int hashCode() {
        return this.values.hashCode();
    }

    @Override
    public String toString() {
        return this.values.toString();
    }
}
