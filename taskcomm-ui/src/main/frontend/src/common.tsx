import { Alert, Button, Descriptions, Select, Space, Spin, Tag, Tooltip, Typography } from 'antd';
import { useLayoutEffect, useRef, useState, type ComponentProps, type Key, type ReactNode } from 'react';
import { Link, useSearchParams } from 'react-router';
import { ApiError, type Origin, type Params, type Window } from './api';
import { useI18n } from './i18n';
import { useMeta } from './meta';

/**
 * Сравнение для сортировки по столбцу: числа — по значению, остальное — как строки (время ISO-8601
 * сравнивается так же верно), пустые — первыми.
 */
/**
 * Значение autocomplete, при котором браузер не подставляет прошлые вводы и сохранённые данные.
 * {@code off} Chrome не слушает, если id поля похож на знакомое ему — имя, адрес, номер. Значение то же,
 * что Ant Design сам ставит в поле поиска выпадающего списка.
 */
export const NO_AUTOFILL = 'new-password';

export function by<TRow>(get: (row: TRow) => string | number | boolean | undefined | null) {
  return (left: TRow, right: TRow): number => {
    const a = get(left);
    const b = get(right);
    if (a === undefined || a === null || b === undefined || b === null) {
      return (a === undefined || a === null ? 0 : 1) - (b === undefined || b === null ? 0 : 1);
    }
    return typeof a === 'number' && typeof b === 'number' ? a - b : String(a).localeCompare(String(b));
  };
}

/** Время по часам базы — в часовом поясе и формате браузера. */
export function useTime(): (value?: string | null) => string {
  const { language } = useI18n();
  const formatter = new Intl.DateTimeFormat(language, { dateStyle: 'short', timeStyle: 'medium' });
  return (value) => (value ? formatter.format(new Date(value)) : '—');
}

const STATUS_COLORS: Record<string, string> = {
  PENDING: 'default',
  RUNNING: 'processing',
  AWAITING_CHILDREN: 'purple',
  AWAITING_PARENT: 'purple',
  AWAITING_RESPONSE: 'purple',
  SUCCEEDED: 'success',
  WARNING: 'warning',
  FAILED: 'error',
  CANCELLED: 'default',
  SKIPPED: 'default',
  ABANDONED: 'default',
  LEASE_LOST: 'error',
  CLOSED: 'success',
  OPEN: 'error',
  PROBING: 'warning',
};

/**
 * Выбор одной попытки в таблице — щелчком по строке или переключателем. Пока ничего не трогали —
 * выбрана последняя.
 */
export function useAttemptSelection<TAttempt extends { id: string }>(attempts: TAttempt[] | undefined) {
  const [chosen, setChosen] = useState<string>();
  const selected = attempts?.find((attempt) => attempt.id === chosen) ?? attempts?.[attempts.length - 1];
  return {
    selected,
    rowSelection: {
      type: 'radio' as const,
      selectedRowKeys: selected ? [selected.id] : [],
      onChange: (next: Key[]) => setChosen(next.length ? String(next[0]) : undefined),
    },
    onRow: (attempt: TAttempt) => ({
      onClick: () => setChosen(attempt.id),
      style: { cursor: 'pointer' },
    }),
  };
}

/** Подпись вкладки с числом элементов; пока число не пришло — без него. */
export function CountLabel({ label, count }: { label: string; count?: number }) {
  return (
    <Space size={6}>
      {label}
      {count !== undefined && <Tag bordered={false}>{count.toLocaleString()}</Tag>}
    </Space>
  );
}

/** Ширина блока, с которой описание раскладывается в две колонки, и с которой — в три. */
const TWO_COLUMNS = 560;
const THREE_COLUMNS = 900;

/** Уже этого подпись встаёт над значением: иначе идентификаторы рвутся посимвольно. */
const LABEL_ABOVE = 420;

/**
 * Описание «подпись — значение», разложенное по ширине своего блока, а не экрана: та же карточка
 * открывается и на странице, и в узкой боковой панели. До первого замера — одна колонка.
 */
export function Facts({ items, max = 3 }: { items: ComponentProps<typeof Descriptions>['items']; max?: number }) {
  const ref = useRef<HTMLDivElement>(null);
  const [width, setWidth] = useState(0);
  useLayoutEffect(() => {
    const element = ref.current;
    if (!element || typeof ResizeObserver === 'undefined') {
      return undefined;
    }
    setWidth(element.clientWidth);
    const observer = new ResizeObserver(([entry]) => setWidth(entry.contentRect.width));
    observer.observe(element);
    return () => observer.disconnect();
  }, []);
  const fits = width >= THREE_COLUMNS ? 3 : width >= TWO_COLUMNS ? 2 : 1;
  return (
    <div ref={ref} className="facts">
      <Descriptions
        size="small"
        column={Math.min(fits, max)}
        layout={width > 0 && width < LABEL_ABOVE ? 'vertical' : 'horizontal'}
        items={items}
      />
    </div>
  );
}

/** Исход попытки: статус и причина целиком — в блоке выбранной попытки. */
export function Outcome({ outcome }: { outcome: { status?: string | null; statusDescription?: string | null } }) {
  const { t } = useI18n();
  return (
    <Facts
      max={1}
      items={[
        { key: 'status', label: t('common.status'), children: <StatusTag status={outcome.status ?? 'RUNNING'} /> },
        {
          key: 'reason',
          label: t('common.reason'),
          children: outcome.statusDescription ? (
            <Typography.Paragraph copyable style={{ margin: 0, whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>
              {outcome.statusDescription}
            </Typography.Paragraph>
          ) : (
            '—'
          ),
        },
      ]}
    />
  );
}

/** Причина в строке таблицы: одна строка с многоточием, целиком — в подсказке. */
export function Reason({ text, width = 280 }: { text?: string | null; width?: number }) {
  if (!text) {
    return <>—</>;
  }
  return (
    <Typography.Text ellipsis={{ tooltip: text }} style={{ maxWidth: width }}>
      {text}
    </Typography.Text>
  );
}

/**
 * Статус — подписью; значение перечисления стартера, как в логах, REST и документации, и причина —
 * в подсказке.
 */
export function StatusTag({ status, description }: { status?: string | null; description?: string | null }) {
  const { code } = useI18n();
  if (!status) {
    return <Tag>—</Tag>;
  }
  return (
    <Tooltip
      title={
        <>
          <div>{status}</div>
          {description && <div>{description}</div>}
        </>
      }
    >
      <Tag color={STATUS_COLORS[status] ?? 'default'}>{code('status', status)}</Tag>
    </Tooltip>
  );
}

/** Фаза — подписью, значение перечисления стартера — в подсказке. */
export function PhaseLabel({ phase }: { phase?: string | null }) {
  const { code } = useI18n();
  if (!phase) {
    return <>—</>;
  }
  return (
    <Tooltip title={phase}>
      <span>{code('phase', phase)}</span>
    </Tooltip>
  );
}

/** Варианты фильтра по статусу: подпись, значение — код. */
export function useStatusOptions(): (statuses: string[]) => { value: string; label: string }[] {
  const { code } = useI18n();
  return (statuses) => statuses.map((status) => ({ value: status, label: code('status', status) }));
}

/** Чей процессор: тип задачи или внешняя система. */
export type ProcessorKind = 'task' | 'system';

export interface ProcessorName {
  id: string;
  title: string;
  description?: string;
}

/**
 * Названия и описания процессоров из meta (docs/adr/0159-processor-title-and-description.md).
 * У типа без процессора на экземпляре названия нет — показывается его имя.
 */
export function useProcessorNames(): (kind: ProcessorKind, id: string) => ProcessorName {
  const meta = useMeta();
  return (kind, id) => {
    const found =
      kind === 'task'
        ? meta.data?.tasks?.types.find((item) => item.type === id)
        : meta.data?.communications?.systems.find((item) => item.system === id);
    return { id, title: found?.title || id, description: found?.description || undefined };
  };
}

/** Вариант списка выбора: название и имя в скобках, если они различаются. */
export function processorOption(name: ProcessorName) {
  return { value: name.id, label: name.title === name.id ? name.id : `${name.title} (${name.id})` };
}

/** Название процессора; имя и описание — в подсказке. */
export function ProcessorTitle({ kind, id }: { kind: ProcessorKind; id: string }) {
  const name = useProcessorNames()(kind, id);
  if (name.title === id && !name.description) {
    return <span>{id}</span>;
  }
  return (
    <Tooltip
      title={
        <>
          {name.title !== id && <div>{id}</div>}
          {name.description && <div>{name.description}</div>}
        </>
      }
    >
      <span>{name.title}</span>
    </Tooltip>
  );
}

/** Имя процессора мелко рядом с названием — в заголовке карточки, где подсказка неудобна. */
export function ProcessorId({ kind, id }: { kind: ProcessorKind; id: string }) {
  const name = useProcessorNames()(kind, id);
  return name.title === id ? null : <Typography.Text type="secondary" code>{id}</Typography.Text>;
}

/** Описание процессора под заголовком карточки. */
export function ProcessorDescription({ kind, id }: { kind: ProcessorKind; id: string }) {
  const name = useProcessorNames()(kind, id);
  return name.description ? <Typography.Text type="secondary">{name.description}</Typography.Text> : null;
}

export function OrphanTag({ orphan }: { orphan: boolean }) {
  const { t } = useI18n();
  if (!orphan) {
    return null;
  }
  return (
    <Tooltip title={t('common.orphanHint')}>
      <Tag color="volcano">{t('common.orphan')}</Tag>
    </Tooltip>
  );
}

/** JSON как есть, с отступами; строка, которая не JSON, — текстом. */
export function JsonBlock({ value }: { value: unknown }) {
  const { t } = useI18n();
  if (value === undefined || value === null) {
    return <Typography.Text type="secondary">{t('common.empty')}</Typography.Text>;
  }
  const text = typeof value === 'string' ? pretty(value) : JSON.stringify(value, null, 2);
  return (
    <pre className="json" data-testid="json">
      {text}
    </pre>
  );
}

function pretty(text: string): string {
  try {
    return JSON.stringify(JSON.parse(text), null, 2);
  } catch {
    return text;
  }
}

/** Загрузка, «нет прав» и ошибка — одинаково на всех экранах. */
export function Loadable({
  loading,
  error,
  children,
}: {
  loading: boolean;
  error: unknown;
  children: () => ReactNode;
}) {
  const { t } = useI18n();
  if (error) {
    if (error instanceof ApiError && error.forbidden) {
      return <Alert type="warning" showIcon title={t('common.forbidden')} />;
    }
    if (error instanceof ApiError && error.status === 404) {
      return <Alert type="info" showIcon title={t('common.notFound')} />;
    }
    const message = error instanceof Error ? error.message : String(error);
    return <Alert type="error" showIcon title={t('common.failed', { error: message })} />;
  }
  if (loading) {
    return <Spin description={t('common.loading')} />;
  }
  return <>{children()}</>;
}

export type WindowChoice = 'default' | 'P1D' | 'P7D' | 'P30D' | 'everywhere';

const WINDOW_MILLIS: Record<'P1D' | 'P7D' | 'P30D', number> = {
  P1D: 24 * 3600 * 1000,
  P7D: 7 * 24 * 3600 * 1000,
  P30D: 30 * 24 * 3600 * 1000,
};

/** Окно чтения истории из адреса экрана; параметры REST считаются в момент запроса. */
export function useWindowChoice(): [WindowChoice, (choice: WindowChoice) => void] {
  const [search, setSearch] = useSearchParams();
  const value = search.get('window');
  const choice: WindowChoice =
    value === 'P1D' || value === 'P7D' || value === 'P30D' || value === 'everywhere' ? value : 'default';
  const set = (next: WindowChoice) => {
    const copy = new URLSearchParams(search);
    if (next === 'default') {
      copy.delete('window');
    } else {
      copy.set('window', next);
    }
    setSearch(copy, { replace: true });
  };
  return [choice, set];
}

export function windowParams(choice: WindowChoice): Params {
  if (choice === 'everywhere') {
    return { everywhere: true };
  }
  if (choice === 'default') {
    return {};
  }
  return { from: new Date(Date.now() - WINDOW_MILLIS[choice]).toISOString() };
}

export function WindowSelect({ value, onChange, searchWindow }: {
  value: WindowChoice;
  onChange: (choice: WindowChoice) => void;
  searchWindow?: string;
}) {
  const { t } = useI18n();
  // Умолчание экземпляра — taskcomm.rest.search-window: совпало с готовым пунктом — выбран он,
  // иначе пункт со своим значением. Выбор умолчания убирает окно из адреса экрана.
  const preset = defaultPreset(searchWindow);
  const presets: { value: WindowChoice; label: string }[] = [
    { value: 'P1D', label: t('window.day') },
    { value: 'P7D', label: t('window.week') },
    { value: 'P30D', label: t('window.month') },
  ];
  const options = [
    ...(preset ? [] : [{ value: 'default' as WindowChoice, label: duration(searchWindow) }]),
    ...presets,
    { value: 'everywhere' as WindowChoice, label: t('window.everywhere') },
  ];
  return (
    <Tooltip title={`${t('window.label')}: ${t('window.hint')}`}>
      <Select<WindowChoice>
        aria-label={t('window.label')}
        prefix={t('window.label')}
        value={value === 'default' ? (preset ?? 'default') : value}
        onChange={(next) => onChange(next === preset ? 'default' : next)}
        style={{ minWidth: 200 }}
        options={options}
      />
    </Tooltip>
  );
}

/** Готовый пункт, совпадающий с окном по умолчанию, или пусто. */
function defaultPreset(searchWindow?: string): WindowChoice | undefined {
  const hours = /^PT(\d+)H$/.exec(searchWindow ?? '');
  const byHours: Record<string, WindowChoice> = { '24': 'P1D', '168': 'P7D', '720': 'P30D' };
  return hours ? byHours[hours[1]] : undefined;
}

/** ISO-8601 вида PT720H — в дни или часы. */
export function duration(iso?: string): string {
  const hours = /^PT(\d+)H$/.exec(iso ?? '');
  if (hours) {
    const value = Number(hours[1]);
    return value % 24 === 0 ? `${value / 24}d` : `${value}h`;
  }
  return iso ?? '—';
}

export function WindowNote({ window }: { window?: Window }) {
  const { t } = useI18n();
  const time = useTime();
  if (!window) {
    return null;
  }
  return (
    <Typography.Text type="secondary">
      {window.everywhere
        ? t('window.shownEverywhere')
        : t('window.shown', { from: time(window.from), to: time(window.to) })}
    </Typography.Text>
  );
}

export function shortId(id: string): string {
  return id.length > 13 ? `${id.substring(0, 8)}…${id.substring(id.length - 4)}` : id;
}

export function taskPath(level: string, id: string, executionCreatedAt?: string): string {
  return `/tasks/${level}/${id}${executionCreatedAt ? `?createdAt=${encodeURIComponent(executionCreatedAt)}` : ''}`;
}

export function communicationPath(id: string, executionCreatedAt?: string): string {
  return `/communications/${id}${executionCreatedAt ? `?createdAt=${encodeURIComponent(executionCreatedAt)}` : ''}`;
}

export function executionPath(id: string, createdAt?: string): string {
  return `/executions/${id}${createdAt ? `?createdAt=${encodeURIComponent(createdAt)}` : ''}`;
}

export function IdLink({ to, id }: { to: string; id: string }) {
  return (
    <Tooltip title={id}>
      <Link to={to}>
        <Typography.Text code>{shortId(id)}</Typography.Text>
      </Link>
    </Tooltip>
  );
}

/** Создатель сущности внутри дела — ссылкой на него. */
export function OriginLink({ origin, executionCreatedAt }: { origin?: Origin; executionCreatedAt: string }) {
  const { t } = useI18n();
  if (!origin) {
    return <Typography.Text type="secondary">—</Typography.Text>;
  }
  const to =
    origin.type === 'COMMUNICATION'
      ? communicationPath(origin.id, executionCreatedAt)
      : taskPath(origin.type, origin.id, executionCreatedAt);
  return (
    <Space size={4}>
      <span>{t(`level.${origin.type}`)}</span>
      <IdLink to={to} id={origin.id} />
    </Space>
  );
}

export function MoreButton({ visible, loading, onClick }: { visible: boolean; loading: boolean; onClick: () => void }) {
  const { t } = useI18n();
  if (!visible) {
    return null;
  }
  return (
    <Button onClick={onClick} loading={loading}>
      {t('common.more')}
    </Button>
  );
}
