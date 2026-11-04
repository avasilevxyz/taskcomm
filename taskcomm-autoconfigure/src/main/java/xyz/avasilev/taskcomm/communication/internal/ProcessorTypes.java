package xyz.avasilev.taskcomm.communication.internal;

import java.util.Arrays;
import org.springframework.core.ResolvableType;
import xyz.avasilev.taskcomm.TaskcommConfigurationException;
import xyz.avasilev.taskcomm.communication.api.CommunicationProcessor;

/**
 * Java-типы доменных форм процессора, восстановленные из его дженерика
 * (docs/adr/0012-payload-storage.md). FQCN в базе не хранится: тип знает только процессор.
 *
 * @param request {@code C} — запрос
 * @param response {@code R} — синхронный ответ
 * @param callback {@code A} — колбэк
 */
record ProcessorTypes(ResolvableType request, ResolvableType response, ResolvableType callback) {

    /**
     * Типы процессора. Все параметры обязаны быть известны по классу: иначе нагрузку не во что
     * разобрать, и лучше узнать это при старте.
     *
     * @param processorClass класс процессора без прокси
     * @param system внешняя система — для сообщения
     * @return типы
     * @throws TaskcommConfigurationException если параметр типа не выводится из класса
     */
    static ProcessorTypes of(final Class<?> processorClass, final String system) {
        final ResolvableType contract = ResolvableType.forClass(processorClass).as(CommunicationProcessor.class);
        return new ProcessorTypes(
                resolved(contract, 0, system, "C"),
                resolved(contract, 1, system, "R"),
                resolved(contract, 2, system, "A"));
    }

    private static ResolvableType resolved(
            final ResolvableType contract, final int index, final String system, final String letter) {
        final ResolvableType generic = contract.getGeneric(index);
        if (generic.resolve() == null) {
            throw new TaskcommConfigurationException("Процессор внешней системы '" + system + "': параметр " + letter
                    + " не выводится из класса процессора. Нагрузку не во что разобрать — укажите конкретные типы "
                    + "в объявлении класса (docs/adr/0012-payload-storage.md).");
        }
        return concrete(generic);
    }

    /**
     * Тот же тип без переменных: тип, выведенный через цепочку интерфейсов, хранит переменную
     * объявления, и Jackson по ней разобрал бы тело в {@code LinkedHashMap}. Параметры, которые
     * не выводятся, становятся {@code Object}.
     */
    private static ResolvableType concrete(final ResolvableType type) {
        final Class<?> raw = type.resolve(Object.class);
        if (!type.hasGenerics()) {
            return ResolvableType.forClass(raw);
        }
        final ResolvableType[] generics =
                Arrays.stream(type.getGenerics()).map(ProcessorTypes::concrete).toArray(ResolvableType[]::new);
        return ResolvableType.forClassWithGenerics(raw, generics);
    }
}
