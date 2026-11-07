package xyz.avasilev.taskcomm.communication.api.processor;

/**
 * Разбор тела по умолчанию для транспорта — JSON у REST и Kafka, XML у SOAP. Стартер передаёт его
 * в методы разбора процессора уже настроенным на целевой тип из дженерика; процессору, которому
 * формат по умолчанию подходит, переопределять эти методы не нужно.
 *
 * @param <TBody> во что разбирается тело
 */
@FunctionalInterface
public interface BodyDecoder<TBody> {

    /**
     * Разбирает тело.
     *
     * @param body сырое тело
     * @return доменная форма
     * @throws RuntimeException если тело не разбирается — это и есть неразбираемое тело
     */
    TBody decode(String body);
}
