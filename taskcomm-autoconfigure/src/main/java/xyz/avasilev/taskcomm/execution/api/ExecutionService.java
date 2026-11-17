package xyz.avasilev.taskcomm.execution.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Операции над самим делом. Состав дела — задачи и коммуникации — читается сервисами подсистем:
 * агрегат пассивен, и сервис дела не знает их типов
 * (docs/adr/0110-execution-composition-reads-in-subsystems.md).
 */
public interface ExecutionService {

    /**
     * Находит дело по идентификатору — например, взятому из лога ({@code tc.executionId}), чтобы
     * получить {@link ExecutionRef} для создания или чтения.
     *
     * <p>Ключа партиционирования в запросе нет, поэтому поиск обходит все партиции таблицы дел.
     * Для разбора и редких вызовов это приемлемо; в горячем пути держите {@link ExecutionRef}
     * у себя.
     *
     * @param executionId идентификатор дела
     * @return дело или пусто, если его нет — например, оно удалено вместе с партицией
     */
    Optional<Execution> find(UUID executionId);

    /**
     * Проверяет, что дело есть: ищет его по ссылке — идентификатору и времени создания, в одной
     * партиции. Ссылку можно собрать из чужих данных, например из параметров запроса, и тогда она
     * указывает на дело, которого нет; чтения по такой ссылке вернули бы пустое дело.
     *
     * <p>По умолчанию — через {@link #find(UUID)} со сверкой времени создания; реализация стартера
     * читает только партицию дела.
     *
     * @param execution ссылка на дело
     * @return дело или пусто, если его нет или время создания не совпадает
     */
    default Optional<Execution> find(final ExecutionRef execution) {
        return this.find(execution.id()).filter(found -> found.createdAt().equals(execution.createdAt()));
    }
}
