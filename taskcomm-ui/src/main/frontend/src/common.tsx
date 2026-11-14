import { CheckOutlined, CopyOutlined, ExpandOutlined } from '@ant-design/icons';
import { Alert, Button, Descriptions, Space, Spin, Tag, Tooltip, Typography } from 'antd';
import { lazy, Suspense, useContext, useLayoutEffect, useRef, useState, type ComponentProps, type Key, type ReactNode } from 'react';
import { Link } from 'react-router';
import { ApiError, type Origin, type Window } from './api';
import { useI18n } from './i18n';
import { DarkContext, useMeta } from './meta';
import { useOpenPanel } from './panels';
import { present, type CodeLanguage } from './xmlFormat';

/** Блок кода на CodeMirror — отдельным модулем: грузится вместе с карточкой, не со списками. */
const CodeView = lazy(() => import('./codeView'));

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
 * Щелчок, которым закончили выделять текст внутри элемента, — не щелчок по нему: человек копирует
 * значение, а не открывает карточку или выбирает строку.
 */
export function selectingText(element: HTMLElement): boolean {
  const selection = window.getSelection();
  return (
    selection !== null &&
    !selection.isCollapsed &&
    selection.toString().trim() !== '' &&
    element.contains(selection.anchorNode)
  );
}

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
      onClick: (event: React.MouseEvent<HTMLElement>) => {
        if (!selectingText(event.currentTarget)) {
          setChosen(attempt.id);
        }
      },
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

/**
 * Описание «подпись — значение», разложенное по ширине своего блока, а не экрана: та же карточка
 * открывается и на странице, и в узкой боковой панели. До первого замера — одна колонка. Значение
 * стоит в строку с подписью, а не поместилось рядом — переносится под неё целиком (стили `.facts`).
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

/**
 * Нагрузка, результат или тело: JSON и XML — с подсветкой, номерами строк и сворачиванием. Над
 * блоком — «Копировать» и «Развернуть»: длинное тело удобнее читать в своей панели на всю высоту.
 *
 * @param title что это за блок — название панели при развороте
 */
export function JsonBlock({ value, title }: { value: unknown; title?: string }) {
  const { t } = useI18n();
  const dark = useContext(DarkContext);
  const openPanel = useOpenPanel();
  if (value === undefined || value === null) {
    return <Typography.Text type="secondary">{t('common.empty')}</Typography.Text>;
  }
  const { text, language } = present(value);
  const expand = () =>
    openPanel({
      kind: 'code',
      label: title ?? t('code.block'),
      extra: <CopyButton text={text} />,
      render: () => <CodeBody text={text} language={language} dark={dark} full />,
    });
  return (
    <div className="code-block">
      <div className="code-block-tools">
        <CopyButton text={text} />
        <Tooltip title={t('code.expand')}>
          <Button size="small" type="text" icon={<ExpandOutlined />} aria-label={t('code.expand')} onClick={expand} />
        </Tooltip>
      </div>
      <CodeBody text={text} language={language} dark={dark} />
    </div>
  );
}

/** Редактор только для чтения; пока он догружается — тот же текст без подсветки, чтобы блок не прыгал. */
export function CodeBody(props: { text: string; language: CodeLanguage; dark: boolean; full?: boolean; reserve?: number }) {
  return (
    <Suspense
      fallback={
        <pre className="json" data-testid="json">
          {props.text}
        </pre>
      }
    >
      <CodeView {...props} />
    </Suspense>
  );
}

/** Копирует текст как он показан — без номеров строк; на пару секунд показывает галочку. */
export function CopyButton({ text }: { text: string }) {
  const { t } = useI18n();
  const [copied, setCopied] = useState(false);
  const copy = () => {
    void navigator.clipboard?.writeText(text).then(() => {
      setCopied(true);
      window.setTimeout(() => setCopied(false), 2000);
    });
  };
  return (
    <Tooltip title={copied ? t('code.copied') : t('code.copy')}>
      <Button
        size="small"
        type="text"
        icon={copied ? <CheckOutlined /> : <CopyOutlined />}
        aria-label={t('code.copy')}
        onClick={copy}
      />
    </Tooltip>
  );
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
