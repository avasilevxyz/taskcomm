import { useCallback, useState } from 'react';

// Ширина боковой панели: тянется мышью за левый край и помнится в браузере — у каждой панели своя.

const PREFIX = 'taskcomm.drawer.';

/** Ширина «large» у antd — с неё панель начинается. */
const DEFAULT_WIDTH = 736;

const MIN_WIDTH = 360;

function clamp(width: number): number {
  return Math.round(Math.max(MIN_WIDTH, Math.min(width, window.innerWidth)));
}

/** Свойства Drawer: ширина и перетаскивание края. */
export function useDrawerSize(name: string) {
  const [size, setSize] = useState(() => {
    try {
      const stored = Number(window.localStorage.getItem(PREFIX + name));
      return stored > 0 ? clamp(stored) : DEFAULT_WIDTH;
    } catch {
      return DEFAULT_WIDTH;
    }
  });
  const onResize = useCallback(
    (next: number) => {
      const width = clamp(next);
      setSize(width);
      try {
        window.localStorage.setItem(PREFIX + name, String(width));
      } catch {
        // не запомнили — ширина действует до перезагрузки
      }
    },
    [name],
  );
  return { size, maxSize: window.innerWidth, resizable: { onResize } };
}
