import { describe, expect, it } from 'vitest';
import { parseChoice, quickLabel, windowParams } from './windowChoice';

describe('окно чтения истории', () => {
  const now = Date.parse('2026-11-14T12:00:00Z');

  it('относительное отсчитывается от момента запроса', () => {
    expect(windowParams({ kind: 'relative', amount: 15, unit: 'm' }, now)).toEqual({ from: '2026-11-14T11:45:00.000Z' });
    expect(windowParams({ kind: 'relative', amount: 2, unit: 'h' }, now)).toEqual({ from: '2026-11-14T10:00:00.000Z' });
    expect(windowParams({ kind: 'relative', amount: 7, unit: 'd' }, now)).toEqual({ from: '2026-11-07T12:00:00.000Z' });
  });

  it('отрезок, везде и окно экземпляра', () => {
    expect(windowParams({ kind: 'absolute', from: '2026-11-01T00:00:00.000Z', to: '2026-11-02T00:00:00.000Z' })).toEqual({
      from: '2026-11-01T00:00:00.000Z',
      to: '2026-11-02T00:00:00.000Z',
    });
    expect(windowParams({ kind: 'everywhere' })).toEqual({ everywhere: true });
    expect(windowParams({ kind: 'default' })).toEqual({});
  });

  it('сохранённый выбор: новый вид, прежние значения списка и мусор', () => {
    expect(parseChoice('{"kind":"relative","amount":90,"unit":"d"}')).toEqual({ kind: 'relative', amount: 90, unit: 'd' });
    expect(parseChoice('P7D')).toEqual({ kind: 'relative', amount: 7, unit: 'd' });
    expect(parseChoice('everywhere')).toEqual({ kind: 'everywhere' });
    expect(parseChoice('{"kind":"relative","amount":0,"unit":"d"}')).toEqual({ kind: 'default' });
    expect(parseChoice('не JSON')).toEqual({ kind: 'default' });
    expect(parseChoice(null)).toEqual({ kind: 'default' });
  });

  it('быстрый диапазон узнаётся по значению', () => {
    expect(quickLabel({ kind: 'relative', amount: 24, unit: 'h' })).toBe('window.h24');
    expect(quickLabel({ kind: 'relative', amount: 3, unit: 'h' })).toBeUndefined();
  });
});
