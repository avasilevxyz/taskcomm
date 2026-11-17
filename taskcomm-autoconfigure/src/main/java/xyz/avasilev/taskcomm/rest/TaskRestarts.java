package xyz.avasilev.taskcomm.rest;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import xyz.avasilev.taskcomm.task.api.TaskRestartResult;
import xyz.avasilev.taskcomm.task.api.TaskService;
import xyz.avasilev.taskcomm.task.api.inspection.TaskInspection;
import xyz.avasilev.taskcomm.task.api.inspection.TaskSnapshot;

/**
 * Перезапуск списком для {@link TaskController} (docs/adr/0169-restart-many.md): поиск каждой сущности
 * в партиции её дела, один вызов {@link TaskService#restartAll} и исход по каждой строке запроса.
 */
final class TaskRestarts {

    private TaskRestarts() {}

    /**
     * Перезапускает найденные сущности одной транзакцией; не найденная — отказ в своей строке.
     *
     * @param tasks сервис задач
     * @param inspection чтение задач — поиск сущностей
     * @param windows окна — партиция дела каждой сущности
     * @param targets сущности запроса
     * @return исход по каждой сущности в порядке запроса
     * @throws IllegalArgumentException если сущностей больше {@link TaskService#RESTART_ALL_LIMIT}
     */
    static List<RestDtos.RestartItemDto> restartAll(
            final TaskService tasks,
            final TaskInspection inspection,
            final RestWindows windows,
            final List<RestDtos.TaskTargetDto> targets) {
        if (targets.size() > TaskService.RESTART_ALL_LIMIT) {
            throw new IllegalArgumentException("Перезапуск списком — не больше " + TaskService.RESTART_ALL_LIMIT
                    + " сущностей за вызов, передано " + targets.size());
        }
        final List<Optional<TaskSnapshot>> found = targets.stream()
                .map(target -> inspection.find(
                        target.level(), target.id(), windows.entity(target.executionCreatedAt(), null, null, false)))
                .toList();
        final Iterator<TaskRestartResult> results = tasks.restartAll(
                        found.stream().flatMap(Optional::stream).toList())
                .iterator();
        final List<RestDtos.RestartItemDto> body = new ArrayList<>(targets.size());
        for (int i = 0; i < targets.size(); i++) {
            final RestDtos.TaskTargetDto target = targets.get(i);
            if (found.get(i).isPresent()) {
                final TaskRestartResult result = results.next();
                body.add(new RestDtos.RestartItemDto(target.level(), target.id(), result.accepted(), result.reason()));
            } else {
                body.add(new RestDtos.RestartItemDto(
                        target.level(), target.id(), false, "не найдена: " + target.level() + " " + target.id()));
            }
        }
        return body;
    }
}
