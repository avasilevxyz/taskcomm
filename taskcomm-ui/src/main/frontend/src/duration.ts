import type { Level, Meta } from './api';

// Длительности и пределы (docs/adr/0171-durations-and-limits.md): формат одинаковый везде — две старшие
// единицы латиницей, секунды — с сотыми, когда они видны: `850ms`, `1.05s`, `12.34s`, `1m 10.25s`, `2h 3m`,
// `1d 4h`. Отбрасывается, а не округляется: 59.999s — это `59.99s`, а не `60s`.

const SECOND = 1000;
const MINUTE = 60 * SECOND;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;

const UNITS: [number, string][] = [
  [DAY, 'd'],
  [HOUR, 'h'],
  [MINUTE, 'm'],
  [SECOND, 's'],
];

/**
 * Длительность в миллисекундах — строкой: меньше секунды — миллисекундами, дальше — две старшие единицы,
 * нулевая не пишется; секунды — с сотыми без хвостовых нулей.
 */
export function formatDuration(ms: number): string {
  const value = Math.max(0, Math.round(ms));
  if (value < SECOND) {
    return `${value}ms`;
  }
  const first = UNITS.findIndex(([size]) => value >= size);
  const parts: string[] = [];
  let rest = value;
  for (const [size, unit] of UNITS.slice(first, first + 2)) {
    if (size === SECOND) {
      // Сотые — отбрасыванием: строка не должна показать целую секунду, которой ещё нет.
      const seconds = Math.floor(rest / 10) / 100;
      if (seconds > 0) {
        parts.push(`${seconds}${unit}`);
      }
      continue;
    }
    const count = Math.floor(rest / size);
    rest -= count * size;
    if (count > 0) {
      parts.push(`${count}${unit}`);
    }
  }
  return parts.join(' ');
}

/**
 * Длительность ISO-8601 в миллисекундах — так их отдаёт сервер ({@code Duration.toString()}: `PT10M`,
 * `PT1H30M`, `PT0.5S`, бывает и с днями `P1DT2H`). Не разобрали — {@code undefined}.
 */
export function parseIsoDuration(text?: string | null): number | undefined {
  const match = /^(-)?P(?:(\d+)D)?(?:T(?:(-?\d+)H)?(?:(-?\d+)M)?(?:(-?\d+(?:\.\d+)?)S)?)?$/.exec(text ?? '');
  if (!match || text === 'P' || text?.endsWith('T')) {
    return undefined;
  }
  const [, minus, days, hours, minutes, seconds] = match;
  const total =
    Number(days ?? 0) * DAY + Number(hours ?? 0) * HOUR + Number(minutes ?? 0) * MINUTE + Number(seconds ?? 0) * SECOND;
  return minus ? -total : total;
}

/** Миллисекунды между двумя моментами ISO-8601. */
export function between(start: string, end: string | number): number {
  return (typeof end === 'number' ? end : Date.parse(end)) - Date.parse(start);
}

/** Длительность закрытой попытки — для сортировки; у незакрытой — {@code undefined}. */
export function attemptMillis(times: { startedAt?: string; finishedAt?: string }): number | undefined {
  return times.startedAt && times.finishedAt ? between(times.startedAt, times.finishedAt) : undefined;
}

/**
 * Предел незавершённого: число — предел в миллисекундах, {@code 'none'} — предела нет (фаза с детьми,
 * сущность целиком), {@code undefined} — неизвестен (старый сервер).
 */
export type Limit = number | 'none' | undefined;

/** Предел одной попытки метода задачи: уровень и фаза называют метод. */
export function taskAttemptLimit(meta: Meta | undefined, taskType: string, level: Level, phase: string): Limit {
  const limit = meta?.tasks?.types
    .find((type) => type.type === taskType)
    ?.limits?.find((item) => item.level === level && item.phase === phase);
  return parseIsoDuration(limit?.maxExecutionTime);
}

/** Предел одной попытки коммуникации системы в фазе. */
export function communicationAttemptLimit(meta: Meta | undefined, system: string, phase: string): Limit {
  const limit = meta?.communications?.systems
    .find((item) => item.system === system)
    ?.limits?.find((item) => item.phase === phase);
  return parseIsoDuration(limit?.maxExecutionTime);
}

/** Сколько система ждёт асинхронного ответа до повторной отправки. */
export function responseTimeout(meta: Meta | undefined, system: string): Limit {
  return parseIsoDuration(meta?.communications?.systems.find((item) => item.system === system)?.responseTimeout);
}

/** Предел попытки сущности: у задач — по уровню и фазе, у коммуникаций — по фазе. */
export function attemptLimit(
  meta: Meta | undefined,
  kind: Level | 'COMMUNICATION',
  processor: string,
  phase: string,
): Limit {
  return kind === 'COMMUNICATION'
    ? communicationAttemptLimit(meta, processor, phase)
    : taskAttemptLimit(meta, processor, kind, phase);
}

/**
 * Окончание: момент ISO-8601 — закончилось; {@code null} — ещё идёт; {@code undefined} — сказать нечего.
 */
export type End = string | null | undefined;

/** Окончание целого по окончаниям частей: идёт хоть одна — идёт целое, иначе — самое позднее. */
export function combineEnds(parts: End[]): End {
  if (parts.some((part) => part === null)) {
    return null;
  }
  const known = parts.filter((part): part is string => typeof part === 'string');
  return known.length === 0 ? undefined : known.reduce((latest, part) => (Date.parse(part) > Date.parse(latest) ? part : latest));
}

/** Самое раннее из начал. */
export function earliest(starts: (string | undefined)[]): string | undefined {
  const known = starts.filter((start): start is string => typeof start === 'string');
  return known.length === 0 ? undefined : known.reduce((first, start) => (Date.parse(start) < Date.parse(first) ? start : first));
}

/**
 * Что показать: законченное — длительностью, идущее — таймером от начала с пределом.
 *
 * @returns {@code undefined}, если начала нет или окончание неизвестно
 */
export function elapsedText(
  start: string | undefined,
  end: End,
  limit: Limit,
  now: number,
  noLimit: string,
): { text: string; running: boolean; over: boolean } | undefined {
  if (!start || end === undefined) {
    return undefined;
  }
  if (end !== null) {
    return { text: formatDuration(between(start, end)), running: false, over: false };
  }
  const spent = between(start, now);
  if (limit === undefined) {
    return { text: formatDuration(spent), running: true, over: false };
  }
  const over = limit !== 'none' && spent > limit;
  return { text: `${formatDuration(spent)} / ${limit === 'none' ? noLimit : formatDuration(limit)}`, running: true, over };
}
