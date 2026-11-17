import { describe, expect, it } from 'vitest';
import type { Meta } from './api';
import {
  attemptLimit,
  combineEnds,
  elapsedText,
  formatDuration,
  parseIsoDuration,
  responseTimeout,
  taskAttemptLimit,
} from './duration';

describe('формат длительности', () => {
  it('меньше секунды — миллисекундами', () => {
    expect(formatDuration(0)).toBe('0ms');
    expect(formatDuration(850)).toBe('850ms');
    expect(formatDuration(-5)).toBe('0ms');
  });

  it('секунды — с сотыми без хвостовых нулей, сотые отбрасываются, а не округляются', () => {
    expect(formatDuration(1_050)).toBe('1.05s');
    expect(formatDuration(12_340)).toBe('12.34s');
    expect(formatDuration(70_250)).toBe('1m 10.25s');
    expect(formatDuration(10_500)).toBe('10.5s');
    expect(formatDuration(59_999)).toBe('59.99s');
    expect(formatDuration(2_094)).toBe('2.09s');
  });

  it('дальше — две старшие единицы, нулевая младшая не пишется', () => {
    expect(formatDuration(12_000)).toBe('12s');
    expect(formatDuration(70_000)).toBe('1m 10s');
    expect(formatDuration(600_000)).toBe('10m');
    expect(formatDuration((2 * 60 + 3) * 60_000 + 59_000)).toBe('2h 3m');
    expect(formatDuration(3_600_000)).toBe('1h');
    expect(formatDuration(28 * 3_600_000)).toBe('1d 4h');
  });
});

describe('длительность ISO-8601', () => {
  it('разбирает то, что пишет Duration.toString()', () => {
    expect(parseIsoDuration('PT10M')).toBe(600_000);
    expect(parseIsoDuration('PT1H30M')).toBe(5_400_000);
    expect(parseIsoDuration('PT0.5S')).toBe(500);
    expect(parseIsoDuration('PT720H')).toBe(720 * 3_600_000);
    expect(parseIsoDuration('P1DT2H')).toBe(26 * 3_600_000);
  });

  it('пустое и непонятное — не разобрано', () => {
    expect(parseIsoDuration(undefined)).toBeUndefined();
    expect(parseIsoDuration('десять минут')).toBeUndefined();
    expect(parseIsoDuration('P')).toBeUndefined();
    expect(parseIsoDuration('PT')).toBeUndefined();
  });
});

describe('предел попытки из /meta', () => {
  const meta: Meta = {
    searchWindow: 'PT720H',
    tasks: {
      types: [
        {
          type: 'orders',
          title: 'orders',
          contract: 'BATCH',
          limits: [
            { level: 'TASK', phase: 'EXPAND', maxExecutionTime: 'PT2H' },
            { level: 'BATCH', phase: 'EXPAND', maxExecutionTime: 'PT10M' },
          ],
        },
      ],
    },
    communications: {
      systems: [
        {
          system: 'bank',
          title: 'bank',
          transport: 'REST',
          limits: [{ phase: 'PROCESS', maxExecutionTime: 'PT5M' }],
          responseTimeout: 'PT1H',
        },
      ],
      manualInbound: false,
    },
  };

  it('у задач метод называют уровень и фаза: раскрытие задачи и группы — разные пределы', () => {
    expect(taskAttemptLimit(meta, 'orders', 'TASK', 'EXPAND')).toBe(7_200_000);
    expect(taskAttemptLimit(meta, 'orders', 'BATCH', 'EXPAND')).toBe(600_000);
    expect(attemptLimit(meta, 'BATCH', 'orders', 'EXPAND')).toBe(600_000);
  });

  it('у коммуникаций — по фазе, и срок ожидания ответа', () => {
    expect(attemptLimit(meta, 'COMMUNICATION', 'bank', 'PROCESS')).toBe(300_000);
    expect(responseTimeout(meta, 'bank')).toBe(3_600_000);
  });

  it('старый сервер без пределов — предел неизвестен', () => {
    expect(taskAttemptLimit(undefined, 'orders', 'TASK', 'EXPAND')).toBeUndefined();
    expect(attemptLimit(meta, 'COMMUNICATION', 'bank', 'HANDLE_RESPONSE')).toBeUndefined();
  });
});

describe('что показать', () => {
  const start = '2026-11-17T10:00:00Z';
  const now = Date.parse(start) + 70_000;

  it('законченное — длительностью от начала до конца', () => {
    expect(elapsedText(start, '2026-11-17T10:00:00.850Z', 600_000, now, 'no limit')).toEqual({
      text: '850ms',
      running: false,
      over: false,
    });
  });

  it('незаконченное — таймер от начала с пределом или «нет предела»', () => {
    expect(elapsedText(start, null, 600_000, now, 'no limit')?.text).toBe('1m 10s / 10m');
    expect(elapsedText(start, null, 'none', now, 'no limit')?.text).toBe('1m 10s / no limit');
    expect(elapsedText(start, null, undefined, now, 'no limit')?.text).toBe('1m 10s');
  });

  it('предел превышен — помечено', () => {
    expect(elapsedText(start, null, 60_000, now, 'no limit')?.over).toBe(true);
  });

  it('без начала и с неизвестным концом — нечего показать', () => {
    expect(elapsedText(undefined, null, 'none', now, 'no limit')).toBeUndefined();
    expect(elapsedText(start, undefined, 'none', now, 'no limit')).toBeUndefined();
  });
});

describe('окончание целого', () => {
  it('идёт хоть одна часть — идёт целое; иначе — самое позднее', () => {
    expect(combineEnds(['2026-11-17T10:00:01Z', null])).toBeNull();
    expect(combineEnds(['2026-11-17T10:00:01Z', '2026-11-17T10:00:03Z', undefined])).toBe('2026-11-17T10:00:03Z');
    expect(combineEnds([undefined])).toBeUndefined();
  });
});
