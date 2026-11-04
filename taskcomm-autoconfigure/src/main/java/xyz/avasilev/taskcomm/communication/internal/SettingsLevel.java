package xyz.avasilev.taskcomm.communication.internal;

/**
 * Уровень, с которого взято итоговое значение настройки, — от сильного к слабому
 * (docs/adr/0061-processor-configuration-annotations.md). Печатается при старте рядом со значением.
 */
enum SettingsLevel {

    /** {@code yaml}: система и фаза. */
    YAML_PHASE("yaml, система и фаза"),

    /** {@code yaml}: система. */
    YAML_SYSTEM("yaml, система"),

    /** Аннотация на методе процессора. */
    METHOD_ANNOTATION("аннотация на методе"),

    /** Аннотация на классе процессора. */
    CLASS_ANNOTATION("аннотация на классе"),

    /** Глобальное значение по умолчанию подсистемы или транспорта. */
    GLOBAL("глобальное значение");

    private final String label;

    SettingsLevel(final String label) {
        this.label = label;
    }

    /**
     * Название уровня для печати.
     *
     * @return название
     */
    String label() {
        return this.label;
    }
}
