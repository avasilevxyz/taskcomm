// Клиент REST taskcomm. Путь REST вставляет контроллер стартера в <meta name="taskcomm-api">;
// при разработке под Vite его нет, и берётся путь по умолчанию — Vite проксирует его в приложение.

const DEFAULT_API = '/tc/api';

export function apiBase(): string {
  const meta = document.querySelector<HTMLMetaElement>('meta[name="taskcomm-api"]');
  const value = meta?.content ?? '';
  return value === '' ? DEFAULT_API : value;
}

export class ApiError extends Error {
  readonly status: number;

  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }

  /** Нет прав: потребитель закрыл путь своей конфигурацией безопасности. */
  get forbidden(): boolean {
    return this.status === 401 || this.status === 403;
  }
}

export async function getJson<TBody>(path: string): Promise<TBody> {
  const response = await fetch(apiBase() + path, {
    credentials: 'same-origin',
    headers: { Accept: 'application/json' },
  });
  if (!response.ok) {
    throw new ApiError(response.status, `${response.status} ${response.statusText}`);
  }
  return (await response.json()) as TBody;
}

/** Описание экземпляра — выдача GET /meta (docs/adr/0150-execution-outline-and-meta.md). */
export interface Meta {
  version?: string;
  searchWindow: string;
  tasks?: { types: { type: string; contract: string }[] };
  communications?: { systems: { system: string; transport: string }[] };
}
