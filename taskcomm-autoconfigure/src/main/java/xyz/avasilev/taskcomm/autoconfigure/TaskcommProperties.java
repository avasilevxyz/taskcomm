package xyz.avasilev.taskcomm.autoconfigure;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.sql.init.DatabaseInitializationMode;

/**
 * Свойства стартера с префиксом {@code taskcomm}.
 *
 * <p>Класс мутабельный, а не {@code record}, хотя для value-объектов записи предпочтительны
 * (см. CLAUDE.md, раздел «Стиль кода»): по правилу стартера каждый бин объявляется явным
 * {@code @Bean}-методом, а конструкторное связывание записи проходит только через
 * {@code @EnableConfigurationProperties}, минуя такой метод. Точкой замены бин свойств
 * не является (docs/adr/0096-no-foreign-type-beans.md).
 */
@Getter
@Setter
public class TaskcommProperties {

    /**
     * Общий выключатель стартера. По умолчанию включён: подключение зависимости не должно
     * требовать дополнительных действий, чтобы получить рабочую функциональность.
     * Выключенный стартер не делает ничего — ни проверки схемы, ни партиций, ни поллеров.
     */
    private boolean enabled = true;

    /** Схема БД и партиции. */
    private Schema schema = new Schema();

    /** Подсистема задач. */
    private Task task = new Task();

    /** Подсистема коммуникаций. */
    private Communication communication = new Communication();

    /** Удаление результатов детей задач после сборки. */
    private Results results = new Results();

    /** Белый список полей поиска по нагрузке. */
    private Search search = new Search();

    /** REST API. */
    private Rest rest = new Rest();

    /** Логи. */
    private Logging logging = new Logging();

    /** Health indicator. */
    private Health health = new Health();

    /**
     * Свойства {@code taskcomm.logging.*} (docs/adr/0034-mdc-business-id.md).
     */
    @Getter
    @Setter
    public static class Logging {

        /**
         * Префикс ключей MDC, которые стартер кладёт вокруг вызова процессора: tc.executionId, tc.taskId,
         * tc.batchId, tc.unitId, tc.communicationId, tc.attempt. Нужен, чтобы ключи стартера не столкнулись
         * с ключами, которые кладёт процессор. Сменив его, обновите и запросы к логам.
         */
        private String mdcPrefix = "tc.";
    }

    /**
     * Свойства {@code taskcomm.health.*} (docs/adr/0051-engine-health.md, docs/adr/0141-observability-instance.md).
     */
    @Getter
    @Setter
    public static class Health {

        /**
         * Сколько своих интервалов поток поллера или heartbeat может не тикать, прежде чем health indicator
         * станет DOWN. Заметно больше одного: паузы сборщика мусора и занятый планировщик не должны давать
         * ложных срабатываний.
         */
        private int missedIntervals = 10;
    }

    /**
     * Свойства {@code taskcomm.rest.*} (docs/adr/0091-rest-disabled-by-default.md,
     * docs/adr/0140-rest-controllers.md).
     */
    @Getter
    @Setter
    public static class Rest {

        /**
         * Включить REST. Выключен по умолчанию: в его составе отмена, перезапуск, создание и тела обмена.
         * Включив, закройте пути в своей конфигурации безопасности — стартер этого не делает, а отдаёт
         * бин RequestMatcher с именем taskcommRequestMatcher, если Spring Security есть в приложении.
         */
        private boolean enabled;

        /** Префикс путей REST. */
        private String basePath = "/api/taskcomm";

        /**
         * Окно чтения истории по умолчанию — по времени создания дела, а не сущности: так устроен ключ
         * партиционирования. Запрос задаёт своё окно параметрами from и to или снимает его everywhere=true.
         */
        private Duration searchWindow = Duration.ofDays(30);
    }

    /**
     * Свойства {@code taskcomm.search.*} (docs/adr/0037-search-field-registry.md).
     */
    @Getter
    @Setter
    public static class Search {

        /**
         * Удалять при старте строки белого списка полей поиска, которых не объявляет ни один
         * процессор. Поиск по удалённому полю перестаёт находить старые нагрузки; сами нагрузки
         * не трогаются. Выключено по умолчанию: удаление не должно происходить оттого, что
         * обновили версию зависимости.
         */
        private boolean removeUndeclaredFields;
    }

    /**
     * Свойства {@code taskcomm.results.*}: когда удаляются результаты детей после успешной сборки
     * (docs/adr/0058-task-result-storage.md, docs/adr/0122-child-results-discard-sweep.md).
     */
    @Getter
    @Setter
    public static class Results {

        /**
         * Когда удалять результаты детей после успешной сборки: never — не удалять, они живут
         * до дропа партиции; immediately — сразу после сборки; after — через discard-after.
         * Удаляются результаты детей всех попыток раскрытия родителя, факт отмечается на родителе.
         */
        private ResultsDiscard discard = ResultsDiscard.AFTER;

        /** Через сколько после успешной сборки удалять результаты детей в режиме after. */
        private Duration discardAfter = Duration.ofDays(7);
    }

    /**
     * Свойства {@code taskcomm.task.*} (docs/adr/0060-retry-and-lease-configuration.md). Числа —
     * отправная точка, а не выверенная рекомендация.
     */
    @Getter
    @Setter
    public static class Task {

        /**
         * Выключатель подсистемы задач. Действует вместе с корневым taskcomm.enabled:
         * выключен любой из двух, и нет ни поллеров, ни heartbeat, ни TaskService
         * (docs/adr/0090-root-switch-disables-everything.md).
         */
        private boolean enabled = true;

        /** Основной поллер. */
        private Poll poll = new Poll();

        /** Аренда — глобальное значение по умолчанию для всех типов и фаз. */
        private Lease lease = new Lease();

        /** Повторы — глобальное значение по умолчанию для всех типов и фаз. */
        private Retry retry = new Retry();

        /**
         * Выделенные поллеры, ключ — имя поллера. Основной их типов не берёт; тип, названный
         * в двух поллерах, — падение при старте.
         */
        private Map<String, Poller> pollers = new LinkedHashMap<>();

        /**
         * Переопределения по типу задачи; ключ: taskType. Сильнее любой аннотации
         * на процессоре (docs/adr/0061-processor-configuration-annotations.md).
         */
        private Map<String, TaskType> types = new LinkedHashMap<>();

        /** Подстраховочный поллер: доводит шаги каскада, не доведённые из-за падения экземпляра. */
        private Safety safety = new Safety();
    }

    /**
     * Свойства {@code taskcomm.task.safety.*} (docs/adr/0126-safety-poller.md).
     */
    @Getter
    @Setter
    public static class Safety {

        /** Такт подстраховочного поллера. */
        private Duration interval = Duration.ofMinutes(1);

        /**
         * Сколько шаг каскада может не двигаться, прежде чем подстраховка его доведёт. Короче —
         * подстраховка дублирует работу живых воркеров, длиннее — застрявшее ждёт дольше.
         */
        private Duration grace = Duration.ofMinutes(1);
    }

    /**
     * Свойства типа задачи {@code taskcomm.task.types.<тип>.*}. Не заданное берётся с уровня
     * слабее: аннотация на методе, на классе, глобальное значение.
     */
    @Getter
    @Setter
    public static class TaskType {

        /**
         * Приоритет задач типа по умолчанию. Шкала по возрастанию: меньше значит важнее.
         * Задаётся только здесь, на уровне типа (docs/adr/0084-priority-only-per-type.md).
         */
        private Integer priority;

        /** Аренда типа. */
        private LeaseOverride lease = new LeaseOverride();

        /** Повторы типа. */
        private RetryOverride retry = new RetryOverride();

        /**
         * Переопределения по фазе: process, expand, collect; сильнее
         * уровня типа. Фаза покрывает все методы фазы: у процессора с группами раскрытие идёт
         * в expand и expandBatch.
         */
        private Map<String, TaskTypePhase> phases = new LinkedHashMap<>();
    }

    /** Свойства фазы типа {@code taskcomm.task.types.<тип>.phases.<фаза>.*}. */
    @Getter
    @Setter
    public static class TaskTypePhase {

        /**
         * Не задаётся: приоритет — свойство задачи, а не попытки. Заданное значение — падение
         * при старте (docs/adr/0084-priority-only-per-type.md); поле есть, чтобы ошибка не прошла
         * молча.
         */
        private Integer priority;

        /** Аренда фазы. */
        private LeaseOverride lease = new LeaseOverride();

        /** Повторы фазы. */
        private RetryOverride retry = new RetryOverride();
    }

    /** Аренда уровня типа или фазы; не заданное — с уровня слабее. */
    @Getter
    @Setter
    public static class LeaseOverride {

        /** На сколько выдаётся и продлевается аренда. */
        private Duration duration;

        /** Такт продления аренды. */
        private Duration heartbeatInterval;

        /** Предел обработки. */
        private Duration maxExecutionTime;
    }

    /** Повторы уровня типа или фазы; не заданное — с уровня слабее. */
    @Getter
    @Setter
    public static class RetryOverride {

        /** Лимит попыток фазы в цикле повторов. */
        private Integer maxAttempts;

        /** Первая задержка backoff. */
        private Duration initialDelay;

        /** Множитель backoff. */
        private Double multiplier;

        /** Верхняя граница задержки. */
        private Duration maxDelay;

        /** Доля джиттера в (0, 1]. */
        private Double jitter;
    }

    /** Свойства {@code taskcomm.task.poll.*} (docs/adr/0063-claim-ordering-and-pollers.md). */
    @Getter
    @Setter
    public static class Poll {

        /** Пауза между захватами при пустой очереди. Не потолок пропускной способности. */
        private Duration interval = Duration.ofSeconds(1);

        /** Верхняя граница одного захвата; захватывается не больше свободных воркеров. */
        private int batchSize = 100;

        /** Размер пула воркеров. */
        private int workers = 8;

        /** Форма захвата. */
        private ClaimOrder claimOrder = ClaimOrder.DEADLINE;

        /**
         * Шаг дедлайна: во сколько времени ожидания обходится один пункт приоритета.
         * При 1m приоритет 100 ждёт на 99 минут дольше приоритета 1.
         */
        private Duration deadlineStep = Duration.ofMinutes(1);

        /**
         * Доля захватов по чистому FIFO при форме quota, строго между 0 и 1. Каждый захват
         * идёт мимо приоритета с этой вероятностью (docs/adr/0115-quota-fifo-share-by-lottery.md).
         */
        private double fifoShare = 0.1;
    }

    /**
     * Свойства выделенного поллера {@code taskcomm.task.pollers.<имя>.*}. Не заданное значение
     * берётся у основного поллера.
     */
    @Getter
    @Setter
    public static class Poller {

        /** Типы задач поллера, хотя бы один. */
        private List<String> types = new ArrayList<>();

        /** Пауза между захватами при пустой очереди. */
        private Duration interval;

        /** Верхняя граница одного захвата. */
        private Integer batchSize;

        /** Размер пула воркеров. */
        private Integer workers;

        /** Форма захвата. */
        private ClaimOrder claimOrder;

        /** Шаг дедлайна. */
        private Duration deadlineStep;

        /** Доля захватов по чистому FIFO при форме quota. */
        private Double fifoShare;
    }

    /** Свойства {@code taskcomm.task.lease.*} (docs/adr/0025-lease-mechanics.md). */
    @Getter
    @Setter
    public static class Lease {

        /** На сколько выдаётся и продлевается аренда. */
        private Duration duration = Duration.ofSeconds(60);

        /** Такт продления аренды — порядка трети аренды, строго меньше её. */
        private Duration heartbeatInterval = Duration.ofSeconds(20);

        /** Предел обработки: дольше аренда не продлевается, и попытка считается неуспешной. */
        private Duration maxExecutionTime = Duration.ofMinutes(30);
    }

    /** Свойства {@code taskcomm.task.retry.*} (docs/adr/0009-retry-and-progress.md). */
    @Getter
    @Setter
    public static class Retry {

        /** Сколько попыток в цикле повторов, не меньше одной. */
        private int maxAttempts = 3;

        /** Задержка после первой неуспешной попытки. */
        private Duration initialDelay = Duration.ofSeconds(5);

        /** Во сколько раз растёт задержка с каждой следующей попыткой. */
        private double multiplier = 2.0;

        /** Потолок задержки. */
        private Duration maxDelay = Duration.ofHours(1);

        /** Доля случайного разброса задержки в обе стороны, в пределах (0, 1]; ноль запрещён. */
        private double jitter = 0.2;
    }

    /** Свойства {@code taskcomm.schema.*}. */
    @Getter
    @Setter
    public static class Schema {

        /**
         * Накатывать ли схему стартера при старте — только для тестов и локальной разработки,
         * там ставится always. Продовую схему не ведёт: для неё changelog
         * db/changelog/taskcomm/master.xml или тот же SQL своим инструментом.
         * embedded на PostgreSQL не срабатывает никогда.
         */
        private DatabaseInitializationMode initialize = DatabaseInitializationMode.NEVER;

        /** Партиции, один набор на всю схему. */
        private Partitions partitions = new Partitions();
    }

    /** Свойства {@code taskcomm.schema.partitions.*} (docs/adr/0080-partition-configuration.md). */
    @Getter
    @Setter
    public static class Partitions {

        /**
         * Ведёт ли партиции стартер. false — партиции создаёт и удаляет потребитель,
         * стартер только проверяет, что партиция на текущий момент есть и запас достаточен.
         */
        private boolean manage = true;

        /** Размер партиции. Выбирается один раз: сменить его на работающей базе нельзя. */
        private PartitionPeriod period = PartitionPeriod.MONTH;

        /** Сколько периодов вперёд держать созданными, не меньше одного. */
        private int createAhead = 3;

        /** Такт обслуживания партиций. */
        private Duration interval = Duration.ofHours(1);

        /** Удаление старых партиций. */
        private Drop drop = new Drop();
    }

    /** Свойства {@code taskcomm.schema.partitions.drop.*}. */
    @Getter
    @Setter
    public static class Drop {

        /**
         * Удалять ли старые партиции. Выключено по умолчанию. Партиция с незавершённой работой
         * не удаляется никогда. Несовместимо с manage: false.
         */
        private boolean enabled;

        /** Глубина хранения: партиция удаляется, когда её верхняя граница старше этого срока. */
        private Duration retention = Duration.ofDays(180);
    }
    /**
     * Свойства {@code taskcomm.communication.*}: подсистема коммуникаций. Поллинг — та же форма, что
     * у задач; аренда и повторы — свои умолчания (docs/rules/configuration.md). Срок ожидания ответа,
     * ограничение скорости и размыкатель появятся вместе с этапами 5c и 5d.
     */
    @Getter
    @Setter
    public static class Communication {

        /** Выключатель подсистемы коммуникаций; корневой taskcomm.enabled выключает и её. */
        private boolean enabled = true;

        /** Основной поллер коммуникаций. */
        private Poll poll = new Poll();

        /** Аренда по умолчанию для всех систем. */
        private CommunicationLease lease = new CommunicationLease();

        /** Повторы по умолчанию для всех систем. */
        private CommunicationRetry retry = new CommunicationRetry();

        /** Выделенные поллеры по именам; не заданное берётся у основного. */
        private Map<String, CommunicationPoller> pollers = new LinkedHashMap<>();

        /** Срок ожидания асинхронного ответа; истёк — повторная отправка. */
        private Duration responseTimeout = Duration.ofHours(1);

        /** Подстраховочный поллер: такт проверки истёкших ожиданий ответа. */
        private CommunicationSafety safety = new CommunicationSafety();

        /** Размыкатель исходящих: пороги и выключатель по умолчанию для всех систем. */
        private CommunicationBreaker circuitBreaker = new CommunicationBreaker();

        /** Переопределения по внешним системам; ключ — имя системы, по нему же ищется процессор. */
        private Map<String, CommunicationSystem> systems = new LinkedHashMap<>();
    }

    /** Размыкатель исходящих: пороги — отправная точка, проверять нагрузкой. */
    @Getter
    @Setter
    public static class CommunicationBreaker {

        /** Включён ли размыкатель для всех систем; по умолчанию выключен. */
        private boolean enabled;

        /** Окно, за которое считается доля отказов. */
        private Duration window = Duration.ofSeconds(60);

        /** Минимальное число вызовов в окне для размыкания. */
        private int minimumCalls = 10;

        /** Доля отказов, при которой система размыкается, в (0, 1]. */
        private double failureRateThreshold = 0.5;

        /** Ожидание до пробы — растёт по правилам backoff. */
        private BreakerWait wait = new BreakerWait();

        /** Проба после ожидания. */
        private BreakerProbe probe = new BreakerProbe();
    }

    /** Ожидание размыкателя до пробы. */
    @Getter
    @Setter
    public static class BreakerWait {

        /** Первое ожидание. */
        private Duration initialDelay = Duration.ofSeconds(30);

        /** Множитель ожидания. */
        private double multiplier = 2.0;

        /** Верхняя граница ожидания. */
        private Duration maxDelay = Duration.ofMinutes(10);

        /** Доля джиттера в (0, 1]; джиттер обязателен. */
        private double jitter = 0.2;
    }

    /** Проба размыкателя. */
    @Getter
    @Setter
    public static class BreakerProbe {

        /** Сколько вызовов в пробе; проба удалась, только если все успешны. */
        private int calls = 3;

        /** Срок пробы: не получены все исходы — снова размыкается. */
        private Duration timeout = Duration.ofMinutes(1);
    }

    /** Размыкатель внешней системы. */
    @Getter
    @Setter
    public static class CommunicationSystemBreaker {

        /** Включён ли у системы; не задано — как у подсистемы. */
        private Boolean enabled;

        /** Считающиеся исключения — полные имена классов; пусто — все. */
        private List<String> exceptions;
    }

    /** Подстраховочный поллер коммуникаций. */
    @Getter
    @Setter
    public static class CommunicationSafety {

        /** Такт проверки истёкших ожиданий ответа. */
        private Duration interval = Duration.ofMinutes(1);
    }

    /** Аренда коммуникаций по умолчанию. */
    @Getter
    @Setter
    public static class CommunicationLease {

        /** На сколько выдаётся и продлевается аренда. */
        private Duration duration = Duration.ofSeconds(60);

        /** Как часто продлевается аренда — заметно меньше аренды. */
        private Duration heartbeatInterval = Duration.ofSeconds(20);

        /** Предел обработки одной попытки; таймаут клиента внешней системы обязан быть короче. */
        private Duration maxExecutionTime = Duration.ofMinutes(5);
    }

    /** Повторы коммуникаций по умолчанию. */
    @Getter
    @Setter
    public static class CommunicationRetry {

        /** Лимит попыток в цикле повторов. */
        private int maxAttempts = 5;

        /** Первая задержка backoff. */
        private Duration initialDelay = Duration.ofSeconds(10);

        /** Множитель backoff. */
        private double multiplier = 2.0;

        /** Верхняя граница задержки backoff. */
        private Duration maxDelay = Duration.ofHours(1);

        /** Доля джиттера в (0, 1]; джиттер обязателен. */
        private double jitter = 0.2;
    }

    /** Выделенный поллер коммуникаций. */
    @Getter
    @Setter
    public static class CommunicationPoller {

        /** Внешние системы поллера; основной их не берёт. */
        private List<String> systems = new ArrayList<>();

        /** Пауза между захватами при пустой очереди. */
        private Duration interval;

        /** Верхняя граница одного захвата. */
        private Integer batchSize;

        /** Размер пула воркеров поллера. */
        private Integer workers;

        /** Форма захвата. */
        private ClaimOrder claimOrder;

        /** Шаг дедлайна формы deadline. */
        private Duration deadlineStep;

        /** Доля захватов по FIFO у формы quota. */
        private Double fifoShare;
    }

    /** Переопределения внешней системы. */
    @Getter
    @Setter
    public static class CommunicationSystem {

        /** Приоритет коммуникаций системы по умолчанию; меньше значит важнее. */
        private Integer priority;

        /** Аренда системы. */
        private LeaseOverride lease = new LeaseOverride();

        /** Повторы системы. */
        private RetryOverride retry = new RetryOverride();

        /**
         * Отказные статусы ответа: код (429) или класс (5xx). Не задано — умолчание
         * транспорта: 5xx и 429 у REST и SOAP, ничего у RAW; пустой список выключает их.
         */
        private List<String> failureStatuses;

        /** Срок ожидания асинхронного ответа от системы. */
        private Duration responseTimeout;

        /** Отправок исходящих в секунду на экземпляр; не задано — без ограничения. */
        private Double rateLimitPerInstance;

        /** Размыкатель системы: включён ли и какие исключения считает. */
        private CommunicationSystemBreaker circuitBreaker = new CommunicationSystemBreaker();

        /** Переопределения по фазам: process, handle-response. */
        private Map<String, CommunicationSystemPhase> phases = new LinkedHashMap<>();
    }

    /** Переопределения фазы внешней системы: только аренда и повторы. */
    @Getter
    @Setter
    public static class CommunicationSystemPhase {

        /** На фазе запрещён: свойство системы — падение при старте. */
        private Integer priority;

        /** На фазе запрещены: свойство системы — падение при старте. */
        private List<String> failureStatuses;

        /** На фазе запрещён: свойство системы — падение при старте. */
        private Duration responseTimeout;

        /** На фазе запрещён: свойство системы — падение при старте. */
        private Double rateLimitPerInstance;

        /** На фазе запрещён: свойство системы — падение при старте. */
        private CommunicationSystemBreaker circuitBreaker = new CommunicationSystemBreaker();

        /** Аренда фазы. */
        private LeaseOverride lease = new LeaseOverride();

        /** Повторы фазы. */
        private RetryOverride retry = new RetryOverride();
    }
}
