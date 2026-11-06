package xyz.avasilev.taskcomm.communication.internal.processor;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.aop.support.AopUtils;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;
import xyz.avasilev.taskcomm.TaskcommConfigurationException;
import xyz.avasilev.taskcomm.TaskcommException;
import xyz.avasilev.taskcomm.communication.api.CommunicationHeaders;
import xyz.avasilev.taskcomm.communication.api.processor.BodyDecoder;
import xyz.avasilev.taskcomm.communication.api.processor.CommunicationProcessor;
import xyz.avasilev.taskcomm.communication.api.processor.SoapCommunicationProcessor;
import xyz.avasilev.taskcomm.communication.internal.ContractShape;

/**
 * Реестр процессоров коммуникаций, построенный при старте из всех бинов {@link CommunicationProcessor}.
 * Ключ — внешняя система; транспорт в ключ не входит (docs/adr/0052-communication-transport-contracts.md).
 * Две системы с одним процессором — нормально, два процессора одной системы — падение при старте.
 *
 * <p>SOAP-процессор, который полагается на разбор XML по умолчанию, требует {@code jackson-dataformat-xml}
 * в classpath потребителя: стартер её не тянет (docs/adr/0135-communication-engine.md). Нет — падение
 * при старте с подсказкой.
 */
public final class CommunicationProcessorRegistry {

    /** Класс XML-маппера Jackson 3 — по нему видно, подключён ли {@code jackson-dataformat-xml}. */
    static final String XML_MAPPER = "tools.jackson.dataformat.xml.XmlMapper";

    private final Map<String, RegisteredProcessor> bySystem;

    private final Map<Class<?>, RegisteredProcessor> byClass;

    /**
     * Строит реестр.
     *
     * @param processors все процессоры коммуникаций приложения
     * @throws TaskcommConfigurationException если система названа двумя процессорами, параметр типа
     *     не выводится из класса или SOAP-процессору нечем разбирать XML
     */
    public CommunicationProcessorRegistry(final Collection<? extends CommunicationProcessor<?, ?, ?>> processors) {
        this(processors, ClassUtils.isPresent(XML_MAPPER, CommunicationProcessorRegistry.class.getClassLoader()));
    }

    /**
     * Строит реестр с явным признаком XML-маппера — для тестов.
     *
     * @param processors все процессоры коммуникаций приложения
     * @param xmlAvailable подключён ли {@code jackson-dataformat-xml}
     */
    public CommunicationProcessorRegistry(
            final Collection<? extends CommunicationProcessor<?, ?, ?>> processors, final boolean xmlAvailable) {
        final Map<String, RegisteredProcessor> systems = new LinkedHashMap<>();
        final Map<Class<?>, RegisteredProcessor> classes = new LinkedHashMap<>();
        for (final CommunicationProcessor<?, ?, ?> processor : processors) {
            final RegisteredProcessor registered = register(processor);
            final RegisteredProcessor previous = systems.putIfAbsent(registered.system(), registered);
            if (previous != null) {
                throw new TaskcommConfigurationException("Внешняя система '" + registered.system() + "' объявлена "
                        + "двумя процессорами: " + previous.processorClass().getName() + " и "
                        + registered.processorClass().getName() + ". Один процессор — одна внешняя система.");
            }
            if (registered.shape() == ContractShape.SOAP && !xmlAvailable && relyOnDefaultXml(registered)) {
                throw new TaskcommConfigurationException("Процессор "
                        + registered.processorClass().getName()
                        + " системы '" + registered.system() + "' разбирает SOAP разбором по умолчанию, а XML-маппера "
                        + "нет: добавьте зависимость tools.jackson.dataformat:jackson-dataformat-xml или "
                        + "переопределите decodeRequest и decodeResponse (docs/adr/0135-communication-engine.md).");
            }
            classes.put(registered.processorClass(), registered);
        }
        this.bySystem = Map.copyOf(systems);
        this.byClass = Map.copyOf(classes);
    }

    /**
     * Процессор системы.
     *
     * @param system внешняя система
     * @return процессор или пусто — коммуникации системы осиротели
     */
    public Optional<RegisteredProcessor> find(final String system) {
        return Optional.ofNullable(this.bySystem.get(system));
    }

    /**
     * Процессор для создания коммуникации: система обязана быть в реестре
     * (docs/adr/0105-orphan-communications.md).
     *
     * @param system внешняя система
     * @return процессор
     * @throws TaskcommException если процессора нет
     */
    public RegisteredProcessor forCreate(final String system) {
        return this.find(system)
                .orElseThrow(() -> new TaskcommException("Внешняя система '" + system + "' не объявлена ни одним "
                        + "процессором. Коммуникацию без процессора некому выполнить, и разбирать её тело нечем "
                        + "(docs/adr/0105-orphan-communications.md)."));
    }

    /**
     * Процессор для создания по классу.
     *
     * @param processorClass класс процессора
     * @return процессор
     * @throws TaskcommException если класса нет в реестре
     */
    public RegisteredProcessor forCreate(final Class<?> processorClass) {
        final RegisteredProcessor processor = this.byClass.get(ClassUtils.getUserClass(processorClass));
        if (processor == null) {
            throw new TaskcommException("Класс " + processorClass.getName() + " не зарегистрирован процессором "
                    + "коммуникаций: объявите его бином Spring.");
        }
        return processor;
    }

    /**
     * Процессор для чтения коммуникаций системы.
     *
     * @param system внешняя система
     * @return процессор
     * @throws TaskcommException если процессора нет — запрос не во что разобрать
     */
    public RegisteredProcessor forRead(final String system) {
        return this.find(system)
                .orElseThrow(() -> new TaskcommException(
                        "У внешней системы '" + system + "' нет процессора: запрос не во что разобрать."));
    }

    /**
     * Все процессоры в порядке регистрации.
     *
     *
     * @return процессоры
     */
    public List<RegisteredProcessor> all() {
        return List.copyOf(this.bySystem.values());
    }

    /**
     * Известные системы — то, что поллеры вправе захватывать.
     *
     * @return внешние системы
     */
    public Set<String> systems() {
        return this.bySystem.keySet();
    }

    private static RegisteredProcessor register(final CommunicationProcessor<?, ?, ?> processor) {
        final String system = processor.externalSystem();
        if (system == null || system.isBlank()) {
            throw new TaskcommConfigurationException(
                    "Процессор " + processor.getClass().getName() + " вернул пустой externalSystem().");
        }
        final Class<?> processorClass = AopUtils.getTargetClass(processor);
        return new RegisteredProcessor(
                system,
                processor,
                processorClass,
                ContractShape.of(processor),
                ProcessorTypes.of(processorClass, system));
    }

    /** Полагается ли SOAP-процессор на разбор по умолчанию: колбэк разбирается, только если он есть. */
    private static boolean relyOnDefaultXml(final RegisteredProcessor processor) {
        if (inherited(processor.processorClass(), "decodeRequest")) {
            return true;
        }
        final boolean callbacks =
                !Void.class.equals(processor.types().callback().resolve());
        return callbacks && inherited(processor.processorClass(), "decodeResponse");
    }

    private static boolean inherited(final Class<?> processorClass, final String name) {
        final Method method = ReflectionUtils.findMethod(
                processorClass, name, String.class, CommunicationHeaders.class, BodyDecoder.class);
        return method == null || method.getDeclaringClass().equals(SoapCommunicationProcessor.class);
    }
}
