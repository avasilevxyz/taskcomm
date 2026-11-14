import { describe, expect, it } from 'vitest';
import type { SearchField } from '../api';
import { cellText, fieldColumns, fieldGroups, fieldValue, fillTitle, valueAt } from './searchFields';

function field(taskType: string, name: string, title?: string, description?: string, level: SearchField['level'] = 'TASK'): SearchField {
  return { level, taskType, name, type: 'STRING', jsonPath: `$.${name}`, title, description, lastDeclaredAt: '' };
}

const FIELDS = [
  field('orders', 'orderId', 'Номер заказа'),
  field('orders', 'amount', 'Сумма', 'В рублях'),
  field('reconciliation', 'orderId', 'Номер заказа'),
  field('reconciliation', 'account'),
  field('export', 'sku', 'Артикул', undefined, 'UNIT'),
];

describe('поля поиска для выбора', () => {
  it('без типа — общие поля одной группой впереди, остальные по типам', () => {
    expect(fieldGroups(FIELDS, 'TASK')).toEqual([
      { options: [{ value: 'orderId', label: 'Номер заказа (orderId)', description: undefined }] },
      { taskType: 'orders', options: [{ value: 'amount', label: 'Сумма (amount)', description: 'В рублях' }] },
      { taskType: 'reconciliation', options: [{ value: 'account', label: 'account', description: undefined }] },
    ]);
  });

  it('с типом — только его поля, включая общие', () => {
    const groups = fieldGroups(FIELDS, 'TASK', 'reconciliation');
    expect(groups.map((group) => group.taskType)).toEqual(['reconciliation']);
    expect(groups[0].options.map((option) => option.value).sort()).toEqual(['account', 'orderId']);
  });

  it('берёт поля только своего уровня', () => {
    expect(fieldGroups(FIELDS, 'UNIT').flatMap((group) => group.options.map((option) => option.value))).toEqual(['sku']);
    expect(fieldGroups(FIELDS, 'BATCH')).toEqual([]);
  });
});

describe('колонки полей поиска', () => {
  const moved = { ...field('orders', 'amount', 'Сумма'), jsonPath: '$.total.rub' };

  it('одна колонка на имя со всеми путями, только тип и уровень таблицы', () => {
    const columns = fieldColumns([...FIELDS, moved], 'TASK', 'orders');
    expect(columns.map((column) => column.name).sort()).toEqual(['amount', 'orderId']);
    expect(columns.find((column) => column.name === 'amount')).toEqual({
      name: 'amount',
      title: 'Сумма',
      description: 'В рублях',
      paths: ['$.amount', '$.total.rub'],
    });
    expect(fieldColumns(FIELDS, 'UNIT', 'orders')).toEqual([]);
  });

  it('значение по вложенному пути, первое непустое из путей', () => {
    expect(valueAt({ customer: { inn: '77' } }, '$.customer.inn')).toBe('77');
    expect(valueAt({ customer: 'x' }, '$.customer.inn')).toBeUndefined();
    const amount = fieldColumns([...FIELDS, moved], 'TASK', 'orders').find((column) => column.name === 'amount');
    expect(amount).toBeDefined();
    expect(fieldValue({ total: { rub: 5 } }, amount!)).toBe(5);
    expect(fieldValue({ amount: 3, total: { rub: 5 } }, amount!)).toBe(3);
  });

  it('ячейка: пустое — прочерк, объект — JSON', () => {
    expect(cellText(undefined)).toBe('—');
    expect(cellText(0)).toBe('0');
    expect(cellText({ a: 1 })).toBe('{"a":1}');
  });
});

describe('название по шаблону', () => {
  it('подставляет значения полей, нет значения или поля — прочерк', () => {
    const columns = fieldColumns(FIELDS, 'TASK', 'orders');
    expect(fillTitle('Заказ {orderId} на {amount}', { orderId: 'A-1', amount: 1500 }, columns)).toBe('Заказ A-1 на 1500');
    expect(fillTitle('Заказ {orderId}', {}, columns)).toBe('Заказ —');
    expect(fillTitle('Заказ {unknown}', { unknown: 1 }, columns)).toBe('Заказ —');
  });
});
