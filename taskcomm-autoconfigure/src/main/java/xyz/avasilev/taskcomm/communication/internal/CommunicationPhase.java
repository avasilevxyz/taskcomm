package xyz.avasilev.taskcomm.communication.internal;

import java.util.Locale;

/**
 * Фаза коммуникации и её попытки (docs/adr/0086-async-response-in-outbound.md): отправка или приём —
 * {@code PROCESS}, обработка асинхронного ответа — {@code HANDLE_RESPONSE}. Значение читается как имя
 * метода процессора. Совпадает с {@code CHECK} колонок {@code phase}.
 */
public enum CommunicationPhase {

    /** Метод {@code process}. */
    PROCESS("process", "process"),

    /** Метод {@code handleResponse}. */
    HANDLE_RESPONSE("handleResponse", "handle-response");

    private final String methodName;

    private final String yamlKey;

    CommunicationPhase(final String methodName, final String yamlKey) {
        this.methodName = methodName;
        this.yamlKey = yamlKey;
    }

    /**
     * Имя метода процессора этой фазы.
     *
     * @return имя метода
     */
    String methodName() {
        return this.methodName;
    }

    /**
     * Ключ фазы в {@code yaml}: {@code systems.<имя>.phases.<ключ>}.
     *
     * @return ключ
     */
    String yamlKey() {
        return this.yamlKey;
    }

    /**
     * Фаза по ключу из {@code yaml}, без учёта регистра.
     *
     * @param key ключ
     * @return фаза или {@code null}, если такой нет
     */
    static CommunicationPhase ofYamlKey(final String key) {
        for (final CommunicationPhase phase : values()) {
            if (phase.yamlKey.equals(key.toLowerCase(Locale.ROOT))) {
                return phase;
            }
        }
        return null;
    }
}
