package xyz.avasilev.taskcomm.communication.internal;

import org.springframework.core.ResolvableType;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
import xyz.avasilev.taskcomm.TaskcommException;

/**
 * Перевод доменных форм коммуникаций в JSON и обратно (docs/adr/0012-payload-storage.md): запрос
 * в {@code request_payload}, ответ в {@code response_payload}. Тип при чтении — из дженерика
 * процессора. Маппер — поле, а не бин; заменить его можно только бином под
 * {@code @TaskcommPayloadMapper} (docs/adr/0096-no-foreign-type-beans.md). Свой у подсистемы:
 * общего кода с задачами нет.
 */
public final class CommunicationCodec {

    private final JsonMapper mapper;

    /**
     * Собирает кодек.
     *
     * @param mapper маппер потребителя под {@code @TaskcommPayloadMapper} или собственный
     */
    public CommunicationCodec(final JsonMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Значение в JSON.
     *
     * @param value значение; {@code null} даёт {@code null}
     * @return JSON или {@code null}
     * @throws TaskcommException если значение не сериализуется
     */
    String write(final Object value) {
        if (value == null) {
            return null;
        }
        try {
            return this.mapper.writeValueAsString(value);
        } catch (final JacksonException failure) {
            throw new TaskcommException(
                    "Не удалось записать в JSON значение типа "
                            + value.getClass().getName() + ": " + failure.getMessage(),
                    failure);
        }
    }

    /**
     * JSON в значение типа из дженерика процессора.
     *
     * @param json JSON; {@code null} даёт {@code null}
     * @param type тип
     * @return значение или {@code null}
     * @throws TaskcommException если JSON не разбирается в этот тип
     */
    Object read(final String json, final ResolvableType type) {
        if (json == null) {
            return null;
        }
        try {
            return this.mapper.readValue(json, this.mapper.getTypeFactory().constructType(type.getType()));
        } catch (final JacksonException failure) {
            throw new TaskcommException("Не удалось разобрать JSON в " + type + ": " + failure.getMessage(), failure);
        }
    }

    /**
     * Разбор тела как JSON в тип из дженерика — разбор по умолчанию REST и Kafka. Ошибку разбора
     * бросает как есть: это и есть неразбираемое тело.
     *
     * @param body сырое тело
     * @param type тип
     * @return значение
     */
    Object decodeJson(final String body, final ResolvableType type) {
        return this.mapper.readValue(body, this.mapper.getTypeFactory().constructType(type.getType()));
    }
}
