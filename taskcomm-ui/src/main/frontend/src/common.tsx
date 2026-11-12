import { Alert, Button, Select, Space, Spin, Tag, Tooltip, Typography } from 'antd';
import type { ReactNode } from 'react';
import { Link, useSearchParams } from 'react-router';
import { ApiError, type Origin, type Params, type Window } from './api';
import { useI18n } from './i18n';

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

/** Статус — значением перечисления стартера, как в логах и документации. */
export function StatusTag({ status, description }: { status?: string | null; description?: string | null }) {
  if (!status) {
    return <Tag>—</Tag>;
  }
  const tag = <Tag color={STATUS_COLORS[status] ?? 'default'}>{status}</Tag>;
  return description ? <Tooltip title={description}>{tag}</Tooltip> : tag;
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
  return (
    <Tooltip title={t('window.hint')}>
      <Select<WindowChoice>
        aria-label={t('window.label')}
        value={value}
        onChange={onChange}
        style={{ minWidth: 180 }}
        options={[
          { value: 'default', label: t('window.default', { window: duration(searchWindow) }) },
          { value: 'P1D', label: t('window.day') },
          { value: 'P7D', label: t('window.week') },
          { value: 'P30D', label: t('window.month') },
          { value: 'everywhere', label: t('window.everywhere') },
        ]}
      />
    </Tooltip>
  );
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
