import { describe, expect, it } from 'vitest';
import { curlCommand, quote } from './curl';
import { formRequest } from './pages/Create';

describe('команда curl', () => {
  it('метод, адрес, заголовки и тело — многострочно', () => {
    expect(
      curlCommand({ url: 'http://host/tc/api/tasks?type=demo-orders', body: '{\n  "a": 1\n}', contentType: 'application/json' }),
    ).toBe(
      [
        "curl --request POST 'http://host/tc/api/tasks?type=demo-orders' \\",
        "  --header 'Accept: application/json' \\",
        "  --header 'Content-Type: application/json' \\",
        "  --data-raw '{\n  \"a\": 1\n}'",
      ].join('\n'),
    );
  });

  it('чтение таблицы — GET без тела и без Content-Type', () => {
    expect(curlCommand({ method: 'GET', url: 'http://host/tc/api/tasks?size=50' })).toBe(
      ["curl --request GET 'http://host/tc/api/tasks?size=50' \\", "  --header 'Accept: application/json'"].join('\n'),
    );
  });

  it('одинарная кавычка в теле не рвёт строку оболочки', () => {
    expect(quote("it's")).toBe(`'it'\\''s'`);
  });
});

describe('запрос формы создания', () => {
  it('задача: тип и непустые параметры, дело с временем — только из подсказки формы', () => {
    expect(
      formRequest('task', { name: 'demo-orders', priority: 5, executionId: 'E-1' }, '{}', 'application/json', {
        executionId: 'E-1',
        executionCreatedAt: '2026-11-14T00:00:00Z',
      }),
    ).toEqual({
      path: '/tasks',
      params: {
        type: 'demo-orders',
        priority: 5,
        notBefore: undefined,
        executionId: 'E-1',
        executionCreatedAt: '2026-11-14T00:00:00Z',
      },
      body: '{}',
      contentType: 'application/json',
    });
  });

  it('исходящая и входящая — путь по системе, у входящей свой тип тела', () => {
    expect(formRequest('outbound', { name: 'demo bank', dedupKey: 'k' }, '{}', 'application/json', {}).path).toBe(
      '/communications/outbound/demo%20bank',
    );
    const inbound = formRequest('inbound', { name: 'demo-warehouse', requestId: 'r-1' }, '<a/>', 'text/xml', {});
    expect(inbound).toMatchObject({ path: '/communications/inbound/demo-warehouse', contentType: 'text/xml' });
    expect(inbound.params).toEqual({ requestId: 'r-1', dedupKey: undefined, correlationId: undefined });
  });
});
