// Раскладка стопки боковых панелей: какие раскрыты, какие свёрнуты в полосу. Панели стоят слева
// направо от старой к новой, новая — у правого края.

/** Ширина свёрнутой панели — вертикальной полосы с названием. */
export const STRIP_WIDTH = 40;

/** Уже этого панель не сжимается. */
export const MIN_PANEL_WIDTH = 360;

/**
 * Какие панели раскрыты. Выбранная раскрыта всегда — по умолчанию это новая. Остальные раскрываются,
 * пока помещаются, от ближних к выбранной к дальним, при равном расстоянии — сначала старшая;
 * что не поместилось — полосы. Сворачиваются, значит, самые дальние от выбранной: у новой — самые
 * старые, а щелчок по полосе слева сворачивает самые новые справа. Свёрнутые вручную — полосы,
 * сколько бы места ни было.
 *
 * @param widths желаемые ширины панелей, слева направо
 * @param focus выбранная панель
 * @param available ширина окна
 * @param collapsed свёрнуты ли панели вручную
 * @returns признак «раскрыта» для каждой панели
 */
export function expanded(widths: number[], focus: number, available: number, collapsed: boolean[] = []): boolean[] {
  const open = widths.map(() => false);
  if (widths.length === 0) {
    return open;
  }
  const chosen = Math.max(0, Math.min(focus, widths.length - 1));
  if (!collapsed[chosen]) {
    open[chosen] = true;
  }
  const used = () =>
    widths.reduce((sum, width, index) => sum + (open[index] ? Math.min(width, available) : STRIP_WIDTH), 0);
  const order = widths
    .map((_, index) => index)
    .filter((index) => index !== chosen && !collapsed[index])
    .sort((a, b) => Math.abs(a - chosen) - Math.abs(b - chosen) || a - b);
  for (const index of order) {
    open[index] = true;
    if (used() > available) {
      open[index] = false;
    }
  }
  return open;
}

/**
 * Ширина по двойному щелчку по краю: шире минимума — до минимума; уже минимальная — до доли окна.
 *
 * @param width текущая ширина панели
 * @param available ширина окна
 * @returns новая ширина
 */
export function toggledWidth(width: number, available: number): number {
  return width > MIN_PANEL_WIDTH ? MIN_PANEL_WIDTH : Math.max(MIN_PANEL_WIDTH, Math.round(available * WIDE_SHARE));
}

/** Доля окна, до которой двойной щелчок расширяет минимальную панель. */
export const WIDE_SHARE = 0.3;

/**
 * Ширина раскрытой панели на экране: желаемая, но не шире того, что оставили остальные, и не уже
 * минимума, если окно это позволяет.
 */
export function shownWidth(widths: number[], open: boolean[], index: number, available: number): number {
  const others = widths.reduce(
    (sum, width, other) => (other === index ? sum : sum + (open[other] ? width : STRIP_WIDTH)),
    0,
  );
  return Math.max(Math.min(MIN_PANEL_WIDTH, available), Math.min(widths[index], available - others));
}
