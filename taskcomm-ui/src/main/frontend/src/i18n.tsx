import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from 'react';

// Строки интерфейса на двух языках (docs/adr/0152-web-interface-frontend-stack.md). Ключи английского
// словаря обязаны совпадать с русским — это проверяет и компилятор, и тест.

const ru = {
  'app.title': 'taskcomm',
  'nav.overview': 'Обзор',
  'nav.tasks': 'Задачи',
  'nav.communications': 'Коммуникации',
  'nav.create': 'Создать',
  'lang.switch': 'English',
  'common.loading': 'Загрузка…',
  'common.forbidden': 'Нет прав. Доступ к taskcomm закрывает конфигурация безопасности приложения.',
  'common.failed': 'Не удалось загрузить: {error}',
  'common.notFound': 'Не найдено.',
  'common.more': 'Показать ещё',
  'common.refresh': 'Обновить',
  'common.all': 'Все',
  'common.empty': 'Пусто',
  'common.id': 'Идентификатор',
  'common.type': 'Тип',
  'common.status': 'Статус',
  'common.reason': 'Причина',
  'common.phase': 'Фаза',
  'common.priority': 'Приоритет',
  'common.created': 'Создана',
  'common.updated': 'Изменена',
  'common.execution': 'Дело',
  'common.origin': 'Кем создана',
  'common.payload': 'Нагрузка',
  'common.result': 'Результат',
  'common.attempts': 'Попытки',
  'common.started': 'Начало',
  'common.finished': 'Конец',
  'common.instance': 'Экземпляр',
  'common.leaseRenewals': 'Продлений аренды',
  'common.cancel': 'Отменить',
  'common.restart': 'Перезапустить',
  'common.confirm': 'Точно?',
  'common.accepted': 'Принято',
  'common.rejected': 'Отклонено: {reason}',
  'common.orphan': 'Нет процессора',
  'common.orphanHint': 'У типа нет процессора: сущность никто не захватит, пока процессор не вернут.',
  'common.count': 'Число',
  'common.version': 'Версия {version}',
  'window.label': 'Окно',
  'window.default': 'По умолчанию ({window})',
  'window.day': 'Сутки',
  'window.week': 'Неделя',
  'window.month': '30 дней',
  'window.everywhere': 'Везде',
  'window.shown': 'Искали в окне {from} — {to}',
  'window.shownEverywhere': 'Искали во всех партициях',
  'window.hint': 'Окно — по времени создания дела, а не сущности.',
  'overview.tasks': 'Задачи по типам',
  'overview.communications': 'Коммуникации по системам',
  'overview.breakers': 'Размыкатели экземпляра {instance}',
  'overview.breakersEmpty': 'Ни у одной системы нет размыкателя на этом экземпляре.',
  'overview.state': 'Состояние',
  'overview.calls': 'Вызовов',
  'overview.failureRate': 'Доля отказов',
  'overview.nextProbe': 'Следующая проба',
  'overview.level': 'Уровень',
  'overview.disabled': 'Подсистема выключена.',
  'level.TASK': 'Задача',
  'level.BATCH': 'Группа',
  'level.UNIT': 'Подзадача',
  'level.COMMUNICATION': 'Коммуникация',
  'tasks.title': 'Задачи',
  'tasks.level': 'Уровень',
  'tasks.field': 'Поле нагрузки',
  'tasks.fieldValue': 'Значение',
  'tasks.unknownField': 'Поле не объявлено. Известные поля: {fields}',
  'tasks.inExecution': 'Только дело {id}',
  'tasks.children': 'Дети',
  'tasks.batches': 'Группы',
  'tasks.units': 'Подзадачи',
  'tasks.parent': 'Родитель',
  'communications.title': 'Коммуникации',
  'communications.direction': 'Направление',
  'communications.system': 'Система',
  'communications.requestId': 'Идентификатор запроса',
  'communications.request': 'Запрос',
  'communications.response': 'Ответ',
  'communications.callback': 'Колбэк',
  'communications.headers': 'Заголовки',
  'communications.body': 'Тело',
  'communications.transport': 'Транспорт',
  'communications.httpStatus': 'Статус ответа',
  'direction.INBOUND': 'Входящая',
  'direction.OUTBOUND': 'Исходящая',
  'execution.title': 'Дело {id}',
  'execution.tree': 'Кто кого создал',
  'execution.timeline': 'В порядке создания',
  'execution.correlation': 'Ключи корреляции',
  'execution.correlationId': 'Ключ',
  'execution.units': 'Подзадачи: {count}',
  'execution.showUnits': 'Показать подзадачи',
  'execution.createdBy': 'создана сущностью {type} {id}',
  'create.title': 'Создать',
  'create.task': 'Задачу',
  'create.communication': 'Исходящую коммуникацию',
  'create.taskType': 'Тип задачи',
  'create.destination': 'Система назначения',
  'create.notBefore': 'Не раньше',
  'create.dedupKey': 'Ключ дедупликации',
  'create.correlationId': 'Ключ корреляции',
  'create.executionId': 'Дело — идентификатор',
  'create.executionCreatedAt': 'Дело — время создания',
  'create.executionHint': 'Пусто — новое дело. Задать — оба поля.',
  'create.submit': 'Создать',
  'create.invalidJson': 'Не JSON: {error}',
  'create.duplicate': 'Дубликат коммуникации {id}',
  'create.noTypes': 'Нет ни одного типа с процессором.',
  'create.format': 'Форматировать',
  'create.warning': 'Создание из интерфейса ставит настоящую работу, а исходящая коммуникация уйдёт во внешнюю систему.',
} as const;

type Key = keyof typeof ru;

const en: Record<Key, string> = {
  'app.title': 'taskcomm',
  'nav.overview': 'Overview',
  'nav.tasks': 'Tasks',
  'nav.communications': 'Communications',
  'nav.create': 'Create',
  'lang.switch': 'Русский',
  'common.loading': 'Loading…',
  'common.forbidden': 'Access denied. Access to taskcomm is controlled by the application security configuration.',
  'common.failed': 'Failed to load: {error}',
  'common.notFound': 'Not found.',
  'common.more': 'Load more',
  'common.refresh': 'Refresh',
  'common.all': 'All',
  'common.empty': 'Empty',
  'common.id': 'ID',
  'common.type': 'Type',
  'common.status': 'Status',
  'common.reason': 'Reason',
  'common.phase': 'Phase',
  'common.priority': 'Priority',
  'common.created': 'Created',
  'common.updated': 'Updated',
  'common.execution': 'Execution',
  'common.origin': 'Created by',
  'common.payload': 'Payload',
  'common.result': 'Result',
  'common.attempts': 'Attempts',
  'common.started': 'Started',
  'common.finished': 'Finished',
  'common.instance': 'Instance',
  'common.leaseRenewals': 'Lease renewals',
  'common.cancel': 'Cancel',
  'common.restart': 'Restart',
  'common.confirm': 'Are you sure?',
  'common.accepted': 'Accepted',
  'common.rejected': 'Rejected: {reason}',
  'common.orphan': 'No processor',
  'common.orphanHint': 'The type has no processor: nothing will claim it until the processor is back.',
  'common.count': 'Count',
  'common.version': 'Version {version}',
  'window.label': 'Window',
  'window.default': 'Default ({window})',
  'window.day': 'Day',
  'window.week': 'Week',
  'window.month': '30 days',
  'window.everywhere': 'Everywhere',
  'window.shown': 'Searched in {from} — {to}',
  'window.shownEverywhere': 'Searched in all partitions',
  'window.hint': 'The window is by execution creation time, not by entity.',
  'overview.tasks': 'Tasks by type',
  'overview.communications': 'Communications by system',
  'overview.breakers': 'Circuit breakers of instance {instance}',
  'overview.breakersEmpty': 'No system has a circuit breaker on this instance.',
  'overview.state': 'State',
  'overview.calls': 'Calls',
  'overview.failureRate': 'Failure rate',
  'overview.nextProbe': 'Next probe',
  'overview.level': 'Level',
  'overview.disabled': 'The subsystem is disabled.',
  'level.TASK': 'Task',
  'level.BATCH': 'Batch',
  'level.UNIT': 'Unit',
  'level.COMMUNICATION': 'Communication',
  'tasks.title': 'Tasks',
  'tasks.level': 'Level',
  'tasks.field': 'Payload field',
  'tasks.fieldValue': 'Value',
  'tasks.unknownField': 'Field is not declared. Known fields: {fields}',
  'tasks.inExecution': 'Only execution {id}',
  'tasks.children': 'Children',
  'tasks.batches': 'Batches',
  'tasks.units': 'Units',
  'tasks.parent': 'Parent',
  'communications.title': 'Communications',
  'communications.direction': 'Direction',
  'communications.system': 'System',
  'communications.requestId': 'Request ID',
  'communications.request': 'Request',
  'communications.response': 'Response',
  'communications.callback': 'Callback',
  'communications.headers': 'Headers',
  'communications.body': 'Body',
  'communications.transport': 'Transport',
  'communications.httpStatus': 'Response status',
  'direction.INBOUND': 'Inbound',
  'direction.OUTBOUND': 'Outbound',
  'execution.title': 'Execution {id}',
  'execution.tree': 'Who created whom',
  'execution.timeline': 'In creation order',
  'execution.correlation': 'Correlation keys',
  'execution.correlationId': 'Key',
  'execution.units': 'Units: {count}',
  'execution.showUnits': 'Show units',
  'execution.createdBy': 'created by {type} {id}',
  'create.title': 'Create',
  'create.task': 'Task',
  'create.communication': 'Outbound communication',
  'create.taskType': 'Task type',
  'create.destination': 'Destination system',
  'create.notBefore': 'Not before',
  'create.dedupKey': 'Deduplication key',
  'create.correlationId': 'Correlation key',
  'create.executionId': 'Execution ID',
  'create.executionCreatedAt': 'Execution creation time',
  'create.executionHint': 'Empty — a new execution. To set it, fill both fields.',
  'create.submit': 'Create',
  'create.invalidJson': 'Not JSON: {error}',
  'create.duplicate': 'Duplicate of communication {id}',
  'create.noTypes': 'No type has a processor.',
  'create.format': 'Format',
  'create.warning': 'Creating from the UI starts real work, and an outbound communication is sent to the external system.',
};

export type Language = 'ru' | 'en';
export type TranslationKey = Key;
export const DICTIONARIES: Record<Language, Record<Key, string>> = { ru, en };

const STORAGE_KEY = 'taskcomm.language';

function initialLanguage(): Language {
  try {
    const stored = window.localStorage.getItem(STORAGE_KEY);
    if (stored === 'ru' || stored === 'en') {
      return stored;
    }
  } catch {
    // хранилище недоступно — берём язык браузера
  }
  return navigator.language.toLowerCase().startsWith('ru') ? 'ru' : 'en';
}

export function format(text: string, values: Record<string, string | number> = {}): string {
  return text.replace(/\{(\w+)\}/g, (all: string, name: string) =>
    name in values ? String(values[name]) : all,
  );
}

interface I18n {
  language: Language;
  setLanguage: (language: Language) => void;
  t: (key: Key, values?: Record<string, string | number>) => string;
}

const I18nContext = createContext<I18n | null>(null);

export function I18nProvider({ children, language: forced }: { children: ReactNode; language?: Language }) {
  const [language, setState] = useState<Language>(forced ?? initialLanguage);
  const setLanguage = useCallback((next: Language) => {
    setState(next);
    try {
      window.localStorage.setItem(STORAGE_KEY, next);
    } catch {
      // не запомнили — переключение всё равно действует до перезагрузки
    }
  }, []);
  const value = useMemo<I18n>(
    () => ({
      language,
      setLanguage,
      t: (key, values) => format(DICTIONARIES[language][key], values),
    }),
    [language, setLanguage],
  );
  return <I18nContext.Provider value={value}>{children}</I18nContext.Provider>;
}

export function useI18n(): I18n {
  const value = useContext(I18nContext);
  if (value === null) {
    throw new Error('useI18n вне I18nProvider');
  }
  return value;
}
