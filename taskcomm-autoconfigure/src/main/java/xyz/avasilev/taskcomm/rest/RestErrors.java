package xyz.avasilev.taskcomm.rest;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import xyz.avasilev.taskcomm.TaskcommException;
import xyz.avasilev.taskcomm.task.api.UnknownSearchFieldException;

/**
 * Ответы на ошибки запроса — только для контроллеров стартера (docs/adr/0140-rest-controllers.md).
 * Обработчики объявлены в базовом классе контроллеров, а не в {@code @ControllerAdvice}: совет
 * действовал бы на контроллеры потребителя.
 */
abstract class RestErrors {

    /**
     * Поле поиска не объявлено — {@code 400} со списком известных.
     *
     * @param unknown исключение
     * @return ответ
     */
    @ExceptionHandler(UnknownSearchFieldException.class)
    ResponseEntity<RestDtos.ErrorDto> unknownField(final UnknownSearchFieldException unknown) {
        return ResponseEntity.badRequest()
                .body(new RestDtos.ErrorDto(unknown.getMessage(), orNull(unknown.knownFields())));
    }

    /**
     * Неверный запрос: нагрузка не разбирается, курсор чужой, окно пустое, тип неизвестен — {@code 400}.
     *
     * @param invalid исключение
     * @return ответ
     */
    @ExceptionHandler({TaskcommException.class, IllegalArgumentException.class})
    ResponseEntity<RestDtos.ErrorDto> invalid(final RuntimeException invalid) {
        return ResponseEntity.badRequest().body(new RestDtos.ErrorDto(invalid.getMessage(), null));
    }

    /**
     * Не найдено.
     *
     * @param what что искали
     * @param <T> тело ответа
     * @return {@code 404}
     */
    static <T> ResponseEntity<T> notFound(final String what) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .header("X-Taskcomm-Not-Found", what)
                .build();
    }

    /**
     * Исход отмены или перезапуска: принят — {@code 200}, отвергнут — {@code 409}.
     *
     * @param accepted принят ли
     * @param reason причина
     * @return ответ
     */
    static ResponseEntity<RestDtos.ResultDto> result(final boolean accepted, final String reason) {
        return ResponseEntity.status(accepted ? HttpStatus.OK : HttpStatus.CONFLICT)
                .body(new RestDtos.ResultDto(accepted, reason));
    }

    /**
     * Список известных полей пустым не бывает в ответе: {@code null} убирает поле.
     *
     * @param fields поля
     * @return поля или {@code null}
     */
    static List<String> orNull(final List<String> fields) {
        return fields == null || fields.isEmpty() ? null : fields;
    }
}
