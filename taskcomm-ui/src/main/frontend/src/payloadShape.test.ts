import { describe, expect, it } from 'vitest';
import type { PayloadShape } from './api';
import { blank, checkShape, template, templateText } from './payloadShape';

const ORDER: PayloadShape = {
  kind: 'OBJECT',
  properties: {
    orderId: { kind: 'STRING' },
    customer: { kind: 'OBJECT', properties: { name: { kind: 'STRING' }, inn: { kind: 'STRING' } } },
    amount: { kind: 'INTEGER' },
    rate: { kind: 'NUMBER' },
    urgent: { kind: 'BOOLEAN' },
    lines: { kind: 'ARRAY', items: { kind: 'OBJECT', properties: { sku: { kind: 'STRING' } } } },
    tags: { kind: 'MAP', items: { kind: 'INTEGER' } },
    state: { kind: 'STRING', values: ['NEW', 'PAID'] },
    extra: { kind: 'ANY' },
  },
};

describe('шаблон нагрузки', () => {
  it('все ключи, значения — заглушки по типу', () => {
    expect(template(ORDER)).toEqual({
      orderId: '',
      customer: { name: '', inn: '' },
      amount: 0,
      rate: 0,
      urgent: false,
      lines: [],
      tags: {},
      state: '',
      extra: null,
    });
    expect(templateText({ kind: 'STRING' })).toBe('""');
  });

  it('шаблон заменяет только пустое', () => {
    expect(blank('{\n  \n}')).toBe(true);
    expect(blank('  ')).toBe(true);
    expect(blank('{"a":1}')).toBe(false);
  });
});

describe('проверка по структуре', () => {
  const codes = (text: string) => checkShape(text, ORDER).map((problem) => problem.code);

  it('шаблон проходит, кроме значения не из перечисления', () => {
    expect(codes(templateText(ORDER))).toEqual(['notOneOf']);
  });

  it('неизвестный ключ, не тот тип, дробь в целом, отсутствующие ключи', () => {
    const text = JSON.stringify({
      orderId: 5,
      customer: { name: 'ООО', inn: '77', kpp: '1' },
      amount: 1.5,
      rate: 2,
      urgent: 'да',
      lines: [{ sku: 1 }],
      tags: { a: 'x' },
      state: 'PAID',
    });
    const problems = checkShape(text, ORDER);
    expect(problems.map((problem) => problem.code)).toEqual([
      'wrongType',
      'unknownKey',
      'wrongType',
      'wrongType',
      'wrongType',
      'wrongType',
      'missingKeys',
    ]);
    const unknown = problems.find((problem) => problem.code === 'unknownKey');
    expect(unknown && text.slice(unknown.from, unknown.to)).toBe('"kpp"');
    expect(problems.at(-1)).toMatchObject({ code: 'missingKeys', keys: ['extra'] });
  });

  it('null подходит любому виду, синтаксис не проверяет', () => {
    expect(checkShape('{"orderId": null}', { kind: 'OBJECT', properties: { orderId: { kind: 'STRING' } } })).toEqual([]);
    expect(checkShape('{"orderId": ', ORDER).every((problem) => problem.code !== 'wrongType')).toBe(true);
  });
});
