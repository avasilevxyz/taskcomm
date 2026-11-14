import { describe, expect, it } from 'vitest';
import { expanded, MIN_PANEL_WIDTH, shownWidth, STRIP_WIDTH, toggledWidth } from './panelLayout';

describe('раскладка панелей', () => {
  it('все раскрыты, пока помещаются', () => {
    expect(expanded([500, 500], 1, 1600)).toEqual([true, true]);
  });

  it('не поместилась — сворачивается самая старая', () => {
    expect(expanded([700, 700, 700], 2, 1600)).toEqual([false, true, true]);
  });

  it('щелчок по полосе слева раскрывает её и сворачивает самые новые справа', () => {
    expect(expanded([700, 700, 700], 0, 1600)).toEqual([true, true, false]);
    expect(expanded([500, 500, 500, 500], 0, 1600)).toEqual([true, true, true, false]);
  });

  it('у выбранной в середине сворачивается дальняя, при равной дальности — новая', () => {
    expect(expanded([500, 500, 500, 500], 1, 1600)).toEqual([true, true, true, false]);
    expect(expanded([500, 500, 500, 500, 500], 3, 1600)).toEqual([false, false, true, true, true]);
  });

  it('выбранная раскрыта, даже если одна не помещается', () => {
    expect(expanded([2000, 700], 1, 1000)).toEqual([false, true]);
    expect(expanded([], 0, 1000)).toEqual([]);
  });

  it('раскрытая не шире оставшегося места', () => {
    const open = [false, true];
    expect(shownWidth([700, 2000], open, 1, 1000)).toBe(1000 - STRIP_WIDTH);
    expect(shownWidth([700, 500], open, 1, 1000)).toBe(500);
  });

  it('свёрнутая вручную — полоса, даже когда место есть', () => {
    expect(expanded([500, 500], 1, 3000, [true, false])).toEqual([false, true]);
    expect(expanded([500, 500], 0, 3000, [true, false])).toEqual([false, true]);
    expect(expanded([500], 0, 3000, [true])).toEqual([false]);
  });

  it('двойной щелчок по краю: до минимума, а минимальную — до 30 % окна', () => {
    expect(toggledWidth(736, 2000)).toBe(MIN_PANEL_WIDTH);
    expect(toggledWidth(MIN_PANEL_WIDTH, 2000)).toBe(600);
    expect(toggledWidth(MIN_PANEL_WIDTH, 1000)).toBe(MIN_PANEL_WIDTH);
  });
});
