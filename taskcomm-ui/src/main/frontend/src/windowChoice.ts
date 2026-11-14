import { useSyncExternalStore } from 'react';
import type { Params } from './api';
import type { TranslationKey } from './i18n';

// Окно чтения истории — одно на весь интерфейс: выбирается в шапке и действует на все выборки.
// Относительное окно («последние 24 часа») считается в момент каждого запроса и сдвигается вместе
// с опросом; абсолютное — неподвижный отрезок. Помнит выбор браузер, как и период опроса.

/** Единица относительного окна. */
export type WindowUnit = 'm' | 'h' | 'd';

/** Выбор окна. {@code default} — окно экземпляра, taskcomm.rest.search-window: до первого выбора. */
export type WindowChoice =
  | { kind: 'default' }
  | { kind: 'relative'; amount: number; unit: WindowUnit }
  | { kind: 'absolute'; from: string; to: string }
  | { kind: 'everywhere' };

const UNIT_MILLIS: Record<WindowUnit, number> = { m: 60_000, h: 3_600_000, d: 86_400_000 };

/** Быстрые диапазоны — с ключом подписи. */
export const QUICK_RANGES: { amount: number; unit: WindowUnit; label: TranslationKey }[] = [
  { amount: 15, unit: 'm', label: 'window.m15' },
  { amount: 1, unit: 'h', label: 'window.h1' },
  { amount: 24, unit: 'h', label: 'window.h24' },
  { amount: 7, unit: 'd', label: 'window.d7' },
  { amount: 30, unit: 'd', label: 'window.d30' },
  { amount: 90, unit: 'd', label: 'window.d90' },
  { amount: 365, unit: 'd', label: 'window.y1' },
];

const KEY = 'taskcomm.window';

/** Значения прежнего выбора из списка — до панели диапазонов. */
const LEGACY: Record<string, WindowChoice> = {
  P1D: { kind: 'relative', amount: 1, unit: 'd' },
  P7D: { kind: 'relative', amount: 7, unit: 'd' },
  P30D: { kind: 'relative', amount: 30, unit: 'd' },
  everywhere: { kind: 'everywhere' },
};

/** Сохранённый выбор из строки; непонятное — окно экземпляра. */
export function parseChoice(stored: string | null): WindowChoice {
  if (stored === null) {
    return { kind: 'default' };
  }
  if (LEGACY[stored]) {
    return LEGACY[stored];
  }
  try {
    const value = JSON.parse(stored) as Partial<WindowChoice> & Record<string, unknown>;
    if (value.kind === 'everywhere') {
      return { kind: 'everywhere' };
    }
    if (value.kind === 'relative' && valid(Number(value.amount), value.unit)) {
      return { kind: 'relative', amount: Number(value.amount), unit: value.unit as WindowUnit };
    }
    if (value.kind === 'absolute' && typeof value.from === 'string' && typeof value.to === 'string') {
      return { kind: 'absolute', from: value.from, to: value.to };
    }
  } catch {
    // не JSON — окно экземпляра
  }
  return { kind: 'default' };
}

function valid(amount: number, unit: unknown): boolean {
  return Number.isInteger(amount) && amount > 0 && (unit === 'm' || unit === 'h' || unit === 'd');
}

/** Параметры окна для REST — в момент запроса: относительное окно отсчитывается от «сейчас». */
export function windowParams(choice: WindowChoice, now: number = Date.now()): Params {
  switch (choice.kind) {
    case 'everywhere':
      return { everywhere: true };
    case 'relative':
      return { from: new Date(now - choice.amount * UNIT_MILLIS[choice.unit]).toISOString() };
    case 'absolute':
      return { from: choice.from, to: choice.to };
    default:
      return {};
  }
}

/** Ключ подписи быстрого диапазона, если выбор с ним совпал. */
export function quickLabel(choice: WindowChoice): TranslationKey | undefined {
  return choice.kind === 'relative'
    ? QUICK_RANGES.find((range) => range.amount === choice.amount && range.unit === choice.unit)?.label
    : undefined;
}

let current: WindowChoice = (() => {
  try {
    return parseChoice(window.localStorage.getItem(KEY));
  } catch {
    return { kind: 'default' } as WindowChoice;
  }
})();
const listeners = new Set<() => void>();

function setChoice(next: WindowChoice): void {
  current = next;
  try {
    if (next.kind === 'default') {
      window.localStorage.removeItem(KEY);
    } else {
      window.localStorage.setItem(KEY, JSON.stringify(next));
    }
  } catch {
    // не запомнили — выбор действует до перезагрузки
  }
  listeners.forEach((listener) => listener());
}

/** Окно чтения истории, общее для всех экранов. */
export function useWindowChoice(): [WindowChoice, (choice: WindowChoice) => void] {
  const choice = useSyncExternalStore(
    (listener) => {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    () => current,
  );
  return [choice, setChoice];
}
