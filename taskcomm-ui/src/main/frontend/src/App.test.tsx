import { QueryClient } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { App } from './App';
import { pivot } from './pages/Overview';

function client(): QueryClient {
  return new QueryClient({ defaultOptions: { queries: { retry: false, refetchInterval: false } } });
}

function routes(answers: Record<string, { status: number; body: unknown }>) {
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
    const path = String(input).replace('/tc/api', '').split('?')[0];
    const answer = answers[path] ?? { status: 404, body: { error: 'нет' } };
    return new Response(JSON.stringify(answer.body), {
      status: answer.status,
      headers: { 'Content-Type': 'application/json' },
    });
  });
}

describe('интерфейс', () => {
  it('показывает «нет прав», когда приложение закрыло REST', async () => {
    routes({ '/meta': { status: 403, body: {} } });

    render(<App client={client()} language="ru" />);

    expect(await screen.findByText(/Нет прав/)).toBeInTheDocument();
  });

  it('обзор показывает типы задач, осиротевшие и системы коммуникаций', async () => {
    routes({
      '/meta': {
        status: 200,
        body: {
          version: '2.0.2',
          searchWindow: 'PT720H',
          tasks: { types: [{ type: 'orders', contract: 'FLAT' }] },
          communications: { systems: [{ system: 'bank', transport: 'REST' }] },
        },
      },
      '/tasks/summary': {
        status: 200,
        body: [
          { level: 'TASK', taskType: 'orders', status: 'FAILED', count: 2, orphan: false },
          { level: 'TASK', taskType: 'forgotten', status: 'PENDING', count: 1, orphan: true },
        ],
      },
      '/communications/summary': {
        status: 200,
        body: [{ direction: 'OUTBOUND', externalSystem: 'bank', status: 'SUCCEEDED', count: 5, orphan: false }],
      },
      '/communications/circuit-breakers': { status: 200, body: [] },
    });

    render(<App client={client()} language="en" />);

    expect(await screen.findByText('orders')).toBeInTheDocument();
    expect(screen.getByText('forgotten')).toBeInTheDocument();
    expect(screen.getByText('No processor')).toBeInTheDocument();
    expect(await screen.findByText('bank')).toBeInTheDocument();
    expect(screen.getByText('Version 2.0.2')).toBeInTheDocument();
  });
});

describe('сводка', () => {
  it('сворачивает строки статусов в строку типа', () => {
    const rows = pivot([
      { group: 'TASK', name: 'orders', status: 'FAILED', count: 2, orphan: false },
      { group: 'TASK', name: 'orders', status: 'SUCCEEDED', count: 5, orphan: false },
      { group: 'UNIT', name: 'orders', status: 'FAILED', count: 1, orphan: true },
    ]);

    expect(rows).toHaveLength(2);
    expect(rows[0]).toMatchObject({ group: 'TASK', counts: { FAILED: 2, SUCCEEDED: 5 }, orphan: false });
    expect(rows[1]).toMatchObject({ group: 'UNIT', orphan: true });
  });
});
