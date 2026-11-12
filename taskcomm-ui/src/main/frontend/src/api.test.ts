import { describe, expect, it, vi } from 'vitest';
import { apiBase, ApiError, getJson, postJson, query } from './api';

function respond(status: number, body: unknown) {
  return vi.spyOn(globalThis, 'fetch').mockResolvedValue(
    new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } }),
  );
}

describe('клиент REST', () => {
  it('берёт путь REST из meta, а без него — путь по умолчанию', () => {
    expect(apiBase()).toBe('/tc/api');
    document.head.innerHTML = '<meta name="taskcomm-api" content="/app/ops/api" />';
    expect(apiBase()).toBe('/app/ops/api');
  });

  it('собирает параметры, пропуская пустые и повторяя списки', () => {
    expect(query({ level: 'UNIT', status: ['FAILED', 'WARNING'], type: undefined, cursor: '', everywhere: true })).toBe(
      '?level=UNIT&status=FAILED&status=WARNING&everywhere=true',
    );
    expect(query({})).toBe('');
  });

  it('шлёт токен CSRF из cookie XSRF-TOKEN в изменяющем запросе', async () => {
    document.cookie = 'XSRF-TOKEN=abc%3D1';
    const fetch = respond(200, { accepted: true });

    await postJson('/tasks/TASK/1/cancel', { executionCreatedAt: '2026-11-12T00:00:00Z' });

    const [url, init] = fetch.mock.calls[0];
    expect(url).toBe('/tc/api/tasks/TASK/1/cancel?executionCreatedAt=2026-11-12T00%3A00%3A00Z');
    expect((init?.headers as Record<string, string>)['X-XSRF-TOKEN']).toBe('abc=1');
    expect(init?.method).toBe('POST');
  });

  it('без cookie заголовка CSRF нет', async () => {
    const fetch = respond(200, { accepted: true });

    await postJson('/communications/1/restart');

    expect((fetch.mock.calls[0][1]?.headers as Record<string, string>)['X-XSRF-TOKEN']).toBeUndefined();
  });

  it('отказ 409 — исход с причиной, а не ошибка', async () => {
    respond(409, { accepted: false, reason: 'уже завершена' });

    const result = await postJson<{ accepted: boolean; reason: string }>('/tasks/TASK/1/cancel');

    expect(result).toEqual({ status: 409, body: { accepted: false, reason: 'уже завершена' } });
  });

  it('403 — ошибка «нет прав», 400 — с телом и известными полями', async () => {
    respond(403, {});
    await expect(getJson('/meta')).rejects.toSatisfy((error: ApiError) => error.forbidden);

    respond(400, { error: 'поле не объявлено', knownFields: ['orderId'] });
    await expect(getJson('/tasks')).rejects.toSatisfy(
      (error: ApiError) => !error.forbidden && error.body?.knownFields?.[0] === 'orderId',
    );
  });
});
