import { describe, expect, it } from 'vitest';
import { STATUSES } from './api';
import { DICTIONARIES, format } from './i18n';

describe('словари', () => {
  it('у русского и английского одни и те же ключи', () => {
    expect(Object.keys(DICTIONARIES.en).sort()).toEqual(Object.keys(DICTIONARIES.ru).sort());
  });

  it('ни одна строка не пуста и подстановки совпадают', () => {
    for (const [key, text] of Object.entries(DICTIONARIES.ru)) {
      const english = DICTIONARIES.en[key as keyof typeof DICTIONARIES.en];
      expect(text.trim(), key).not.toBe('');
      expect(english.trim(), key).not.toBe('');
      const names = (value: string) => [...value.matchAll(/\{(\w+)\}/g)].map((match) => match[1]).sort();
      expect(names(english), key).toEqual(names(text));
    }
  });

  it('подставляет значения и оставляет неизвестные как есть', () => {
    expect(format('Дело {id}: {missing}', { id: 'A-1' })).toBe('Дело A-1: {missing}');
  });

  it('у каждого статуса фильтров и каждой фазы есть подпись', () => {
    const codes = [
      ...[...new Set(Object.values(STATUSES).flat())].map((status) => `status.${status}`),
      ...['EXPAND', 'PROCESS', 'COLLECT', 'HANDLE_RESPONSE'].map((phase) => `phase.${phase}`),
    ];
    for (const key of codes) {
      expect(DICTIONARIES.ru, key).toHaveProperty([key]);
    }
  });
});
