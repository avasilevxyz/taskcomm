import { Tooltip, Typography } from 'antd';
import { Fragment, useSyncExternalStore, type ReactNode } from 'react';
import { elapsedText, formatDuration, type End, type Limit } from './duration';
import { useI18n } from './i18n';

// Один такт на всю страницу: таймеры незавершённого перерисовываются раз в секунду все вместе, без
// своего интервала у каждого узла.
const listeners = new Set<() => void>();
let timer: ReturnType<typeof setInterval> | undefined;
let now = Date.now();

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  if (timer === undefined) {
    now = Date.now();
    timer = setInterval(() => {
      now = Date.now();
      listeners.forEach((notify) => notify());
    }, 1000);
  }
  return () => {
    listeners.delete(listener);
    if (listeners.size === 0 && timer !== undefined) {
      clearInterval(timer);
      timer = undefined;
    }
  };
}

/** Текущее время, обновляется раз в секунду, пока его кто-то читает. */
function useNow(): number {
  return useSyncExternalStore(subscribe, () => now);
}

/** Тикающая часть отдельно: подписывается на такт только незавершённое. */
function Running({ start, limit, total }: { start: string; limit: Limit; total?: boolean }) {
  const current = useNow();
  return <Shown start={start} end={null} limit={limit} now={current} total={total} />;
}

function Shown({ start, end, limit, now: at, total }: { start: string; end: End; limit: Limit; now: number; total?: boolean }) {
  const { t } = useI18n();
  const shown = elapsedText(start, end, limit, at, t('duration.noLimit'));
  if (!shown) {
    return null;
  }
  const text = total ? t('duration.total', { value: shown.text }) : shown.text;
  return (
    <Tooltip
      title={
        shown.over
          ? t('duration.over')
          : shown.running
            ? t('duration.running')
            : t(total ? 'duration.totalHint' : 'duration.attemptHint')
      }
    >
      <Typography.Text type={shown.over ? 'danger' : 'secondary'} style={{ whiteSpace: 'nowrap' }}>
        {text}
      </Typography.Text>
    </Tooltip>
  );
}

/** Сведения о времени одной строкой через точку; пустые части пропускаются. */
export function Dotted({ parts }: { parts: ReactNode[] }) {
  const shown = parts.filter((part) => part !== null && part !== undefined && part !== false && part !== '');
  return (
    <>
      {shown.map((part, index) => (
        // Части строки постоянны по порядку — индекс годится ключом.
        <Fragment key={index}>
          {index > 0 && <Typography.Text type="secondary">·</Typography.Text>}
          {part}
        </Fragment>
      ))}
    </>
  );
}

/** Работа — сумма попыток; идущие считаются до текущего момента и тикают. */
export function WorkTime({ closedMs, openStarts }: { closedMs: number; openStarts: string[] }) {
  return openStarts.length > 0 ? (
    <RunningWork closedMs={closedMs} openStarts={openStarts} />
  ) : (
    <WorkText ms={closedMs} />
  );
}

function RunningWork({ closedMs, openStarts }: { closedMs: number; openStarts: string[] }) {
  const current = useNow();
  return <WorkText ms={closedMs + openStarts.reduce((sum, start) => sum + Math.max(0, current - Date.parse(start)), 0)} />;
}

function WorkText({ ms }: { ms: number }) {
  const { t } = useI18n();
  return (
    <Tooltip title={t('duration.workHint')}>
      <Typography.Text type="secondary" style={{ whiteSpace: 'nowrap' }}>
        {t('duration.work', { value: formatDuration(ms) })}
      </Typography.Text>
    </Tooltip>
  );
}

/**
 * Длительность от начала до окончания, а незавершённое — таймер от начала с пределом: `1m 10s / 10m`,
 * `1m 10s / no limit` (docs/adr/0171-durations-and-limits.md).
 *
 * @param total подписать как суммарное время родителя
 */
export function Elapsed({ start, end, limit, total }: { start?: string; end: End; limit?: Limit; total?: boolean }) {
  if (!start || end === undefined) {
    return null;
  }
  return end === null ? (
    <Running start={start} limit={limit} total={total} />
  ) : (
    <Shown start={start} end={end} limit={limit} now={0} total={total} />
  );
}
