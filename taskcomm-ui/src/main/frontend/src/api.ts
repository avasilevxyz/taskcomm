// Клиент REST taskcomm (docs/adr/0140-rest-controllers.md, docs/adr/0150-execution-outline-and-meta.md).
// Путь REST вставляет контроллер стартера в <meta name="taskcomm-api">; при разработке под Vite
// его нет, и берётся путь по умолчанию — Vite проксирует его в приложение.

const DEFAULT_API = '/tc/api';

export function apiBase(): string {
  const meta = document.querySelector<HTMLMetaElement>('meta[name="taskcomm-api"]');
  const value = meta?.content ?? '';
  return value === '' ? DEFAULT_API : value;
}

/** Заголовок интерфейса — taskcomm.ui.title из <meta name="taskcomm-title"> (docs/adr/0154-ui-shell.md). */
export function appTitle(): string {
  const value = document.querySelector<HTMLMetaElement>('meta[name="taskcomm-title"]')?.content ?? '';
  return value === '' ? 'taskcomm' : value;
}

/** Базовый путь экранов — из <base href>, который вставляет контроллер; без него — корень. */
export function routerBase(): string {
  if (!document.querySelector('base')) {
    return '';
  }
  return new URL(document.baseURI).pathname.replace(/\/$/, '');
}

export class ApiError extends Error {
  readonly status: number;
  readonly body: ErrorBody | undefined;

  constructor(status: number, message: string, body?: ErrorBody) {
    super(message);
    this.status = status;
    this.body = body;
  }

  /** Нет прав: потребитель закрыл путь своей конфигурацией безопасности. */
  get forbidden(): boolean {
    return this.status === 401 || this.status === 403;
  }
}

export type Params = Record<string, string | number | boolean | string[] | undefined | null>;

export function query(params: Params): string {
  const search = new URLSearchParams();
  for (const [name, value] of Object.entries(params)) {
    if (value === undefined || value === null || value === '') {
      continue;
    }
    if (Array.isArray(value)) {
      value.forEach((item) => search.append(name, item));
    } else {
      search.append(name, String(value));
    }
  }
  const text = search.toString();
  return text === '' ? '' : `?${text}`;
}

async function failure(response: Response): Promise<ApiError> {
  let body: ErrorBody | undefined;
  try {
    body = (await response.json()) as ErrorBody;
  } catch {
    body = undefined;
  }
  return new ApiError(response.status, body?.error ?? `${response.status} ${response.statusText}`, body);
}

export async function getJson<TBody>(path: string, params: Params = {}): Promise<TBody> {
  const response = await fetch(apiBase() + path + query(params), {
    credentials: 'same-origin',
    headers: { Accept: 'application/json' },
  });
  if (!response.ok) {
    throw await failure(response);
  }
  return (await response.json()) as TBody;
}

/** Токен CSRF из cookie XSRF-TOKEN — так его отдаёт CookieCsrfTokenRepository Spring Security. */
export function csrfToken(): string | undefined {
  const cookie = document.cookie
    .split(';')
    .map((part) => part.trim())
    .find((part) => part.startsWith('XSRF-TOKEN='));
  return cookie === undefined ? undefined : decodeURIComponent(cookie.substring('XSRF-TOKEN='.length));
}

/**
 * Изменяющий запрос. Отказ отмены и перезапуска (409) — не ошибка, а исход: тело его объясняет.
 */
export async function postJson<TBody>(
  path: string,
  params: Params = {},
  body?: string,
  contentType = 'application/json',
): Promise<{ status: number; body: TBody }> {
  const headers: Record<string, string> = { Accept: 'application/json' };
  const token = csrfToken();
  if (token !== undefined) {
    headers['X-XSRF-TOKEN'] = token;
  }
  if (body !== undefined) {
    headers['Content-Type'] = contentType;
  }
  const response = await fetch(apiBase() + path + query(params), {
    method: 'POST',
    credentials: 'same-origin',
    headers,
    body,
  });
  if (!response.ok && response.status !== 409) {
    throw await failure(response);
  }
  return { status: response.status, body: (await response.json()) as TBody };
}

export type Level = 'TASK' | 'BATCH' | 'UNIT';
export type Direction = 'INBOUND' | 'OUTBOUND';

export interface ExecutionRef {
  id: string;
  createdAt: string;
}

export interface Origin {
  type: 'TASK' | 'BATCH' | 'UNIT' | 'COMMUNICATION';
  id: string;
  attemptId?: string;
  relation: string;
}

export interface Window {
  from?: string;
  to?: string;
  everywhere: boolean;
}

export interface Page<TItem> {
  items: TItem[];
  next?: string;
  window: Window;
}

export interface TaskItem {
  id: string;
  level: Level;
  taskType: string;
  execution: ExecutionRef;
  parents?: { taskId?: string; batchId?: string };
  state: {
    status: string;
    statusDescription?: string;
    phase?: string;
    priority: number;
    createdAt: string;
    updatedAt: string;
    orphan: boolean;
  };
  origin?: Origin;
  payload: unknown;
}

export interface Outcome {
  status?: string;
  statusDescription?: string;
}

export interface Times {
  startedAt?: string;
  finishedAt?: string;
}

export interface TaskAttempt {
  id: string;
  phase: string;
  outcome: Outcome;
  times: Times;
  lockedBy?: string;
  leaseRenewals: number;
  result?: unknown;
}

/** Ключи коммуникации: дедупликации и корреляции. */
export interface CommunicationKeys {
  dedup: { subject?: string | null; key: string; createdAt: string }[];
  correlation: { externalSystem: string; correlationId: string; communicationId: string; createdAt: string }[];
}

export interface CommunicationItem {
  id: string;
  direction: Direction;
  externalSystem: string;
  execution: ExecutionRef;
  state: {
    status: string;
    statusDescription?: string;
    phase: string;
    contractShape?: string;
    priority: number;
    createdAt: string;
    updatedAt: string;
    orphan: boolean;
  };
  origin?: Origin;
  requestPayload?: unknown;
}

export interface ExchangeMessage {
  body?: string;
  headers?: Record<string, string[]>;
  id?: string;
  at?: string;
  status?: number;
}

export interface CommunicationAttempt {
  id: string;
  phase: string;
  outcome: Outcome;
  times: Times;
  lockedBy?: string;
  exchange?: {
    request?: ExchangeMessage;
    response?: ExchangeMessage;
    callbackPayload?: unknown;
    responsePayload?: unknown;
  };
}

export interface UnitCount {
  taskId: string;
  batchId: string | null;
  status: string;
  count: number;
}

export interface CorrelationKey {
  externalSystem: string;
  correlationId: string;
  communicationId: string;
  createdAt: string;
}

export interface ExecutionOutline {
  execution: ExecutionRef;
  tasks: TaskItem[];
  batches: TaskItem[];
  unitCounts: UnitCount[];
  communications: CommunicationItem[];
  correlationKeys: CorrelationKey[];
}

export interface TaskTypeSummary {
  level: Level;
  taskType: string;
  status: string;
  count: number;
  orphan: boolean;
}

export interface CommunicationSummary {
  direction: Direction;
  externalSystem: string;
  status: string;
  count: number;
  orphan: boolean;
}

export interface CircuitBreaker {
  instance: string;
  externalSystem: string;
  state: string;
  calls: number;
  failureRate: number;
  nextProbeAt?: string | null;
}

export interface SearchField {
  level: Level;
  taskType: string;
  name: string;
  type: string;
  jsonPath: string;
  title?: string | null;
  description?: string | null;
  lastDeclaredAt: string;
}

export interface ActionResult {
  accepted: boolean;
  reason?: string;
}

export interface Created {
  id: string;
  execution: ExecutionRef;
  status: string;
  duplicateOf?: string;
  /** Почему входящая сразу FAILED — у неразбираемого тела. */
  reason?: string;
}

export interface ErrorBody {
  error: string;
  knownFields?: string[];
}

/** Описание экземпляра — выдача GET /meta (docs/adr/0150-execution-outline-and-meta.md). */
export interface Meta {
  version?: string;
  /** Имя ответившего экземпляра — то же, что в locked_by попыток. */
  instance?: string;
  searchWindow: string;
  tasks?: {
    types: { type: string; title: string; description?: string | null; contract: string; defaultPriority?: number; poller?: string }[];
  };
  communications?: {
    systems: {
      system: string;
      title: string;
      description?: string | null;
      transport: string;
      defaultPriority?: number;
      poller?: string;
    }[];
    /** Включён ли ручной приём входящих (docs/adr/0153-ui-creation-refinements.md). */
    manualInbound: boolean;
  };
}

/** Статусы по уровням — значения перечислений стартера. */
export const STATUSES: Record<Level | 'COMMUNICATION', string[]> = {
  TASK: ['PENDING', 'RUNNING', 'AWAITING_CHILDREN', 'SUCCEEDED', 'WARNING', 'FAILED', 'CANCELLED', 'SKIPPED'],
  BATCH: [
    'AWAITING_PARENT',
    'PENDING',
    'RUNNING',
    'AWAITING_CHILDREN',
    'SUCCEEDED',
    'WARNING',
    'FAILED',
    'CANCELLED',
    'SKIPPED',
    'ABANDONED',
  ],
  UNIT: ['AWAITING_PARENT', 'PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'SKIPPED', 'ABANDONED'],
  COMMUNICATION: ['PENDING', 'RUNNING', 'AWAITING_RESPONSE', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'SKIPPED'],
};
