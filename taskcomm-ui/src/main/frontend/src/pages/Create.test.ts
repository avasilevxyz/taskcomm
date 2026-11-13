import { describe, expect, it } from 'vitest';
import { by } from '../common';
import { loadDraft, parseMoment, saveDraft } from './Create';

describe('«не раньше»', () => {
  it('пусто — сразу', () => {
    expect(parseMoment('')).toBeNull();
    expect(parseMoment('   ')).toBeNull();
    expect(parseMoment(undefined)).toBeNull();
  });

  it('ISO-8601 с зоной — как есть', () => {
    expect(parseMoment('2026-11-12T07:00:00Z')?.toISOString()).toBe('2026-11-12T07:00:00.000Z');
    expect(parseMoment('2026-11-12T10:00:00+03:00')?.toISOString()).toBe('2026-11-12T07:00:00.000Z');
  });

  it('без зоны — местное время браузера, с пробелом или T', () => {
    const local = new Date(2026, 10, 12, 10, 0, 0);
    expect(parseMoment('2026-11-12 10:00')?.getTime()).toBe(local.getTime());
    expect(parseMoment('2026-11-12T10:00')?.getTime()).toBe(local.getTime());
  });

  it('не разобралось — undefined', () => {
    expect(parseMoment('завтра')).toBeUndefined();
    expect(parseMoment('2026-13-45 99:99')).toBeUndefined();
  });
});

describe('сортировка по столбцу', () => {
  it('числа — по значению, строки — по алфавиту, пустые — первыми', () => {
    expect([10, 2, 33].sort(by((value: number) => value))).toEqual([2, 10, 33]);
    expect(['b', 'a'].sort(by((value: string) => value))).toEqual(['a', 'b']);
    expect([{ v: 'x' }, { v: undefined }].sort(by((row: { v?: string }) => row.v))).toEqual([{ v: undefined }, { v: 'x' }]);
  });
});

describe('черновик формы', () => {
  it('сохраняется по вкладке формы, читается и удаляется', () => {
    saveDraft('task', { values: { name: 'orders', priority: 5 }, payload: '{"a":1}' });

    expect(loadDraft('task')).toEqual({ values: { name: 'orders', priority: 5 }, payload: '{"a":1}' });
    expect(loadDraft('outbound')).toBeUndefined();

    saveDraft('task', undefined);
    expect(loadDraft('task')).toBeUndefined();
  });

  it('испорченное значение в хранилище — черновика нет', () => {
    window.sessionStorage.setItem('taskcomm.draft.inbound', 'не JSON');
    expect(loadDraft('inbound')).toBeUndefined();
    window.sessionStorage.setItem('taskcomm.draft.inbound', '{"values":{}}');
    expect(loadDraft('inbound')).toBeUndefined();
  });
});
