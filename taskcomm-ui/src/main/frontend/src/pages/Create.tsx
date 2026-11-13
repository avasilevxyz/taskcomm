import { useMutation } from '@tanstack/react-query';
import { Alert, Button, Card, Flex, Form, Input, InputNumber, Select, Space, Tabs, Tag, Typography } from 'antd';
import { useContext, useEffect, useReducer, useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router';
import { ApiError, postJson, type Created, type Meta, type Params } from '../api';
import { communicationPath, Loadable, NO_AUTOFILL, processorOption, shortId, taskPath } from '../common';
import { useI18n } from '../i18n';
import { JsonEditor } from '../JsonEditor';
import { DarkContext, useMeta } from '../meta';

// Создание задачи, исходящей и — при включённом ручном приёме — входящей
// (docs/adr/0153-ui-creation-refinements.md). Одна и та же панель живёт на странице /create
// и в боковой панели поверх любого экрана.

/** Дело, в которое ставится работа: из экрана дела известна пара, из формы — идентификатор. */
export interface CreatePrefill {
  executionId?: string;
  executionCreatedAt?: string;
}

export type Kind = 'task' | 'outbound' | 'inbound';

export interface Values {
  name?: string;
  priority?: number;
  notBefore?: string;
  executionId?: string;
  dedupKey?: string;
  correlationId?: string;
  requestId?: string;
  contentType?: string;
}

const EMPTY_PAYLOAD = '{\n  \n}';

/** Черновик вкладки формы: поля и нагрузка. */
export interface Draft {
  values: Values;
  payload: string;
}

function draftKey(kind: Kind): string {
  return `taskcomm.draft.${kind}`;
}

/**
 * Черновик формы из sessionStorage вкладки браузера: переживает закрытие панели и перезагрузку страницы,
 * у другой вкладки — свой. Хранилище недоступно или в нём не то — черновика нет.
 */
export function loadDraft(kind: Kind): Draft | undefined {
  try {
    const raw = window.sessionStorage.getItem(draftKey(kind));
    const draft = raw ? (JSON.parse(raw) as Draft) : undefined;
    return draft && typeof draft.payload === 'string' && typeof draft.values === 'object' ? draft : undefined;
  } catch {
    return undefined;
  }
}

export function saveDraft(kind: Kind, draft: Draft | undefined): void {
  try {
    if (draft) {
      window.sessionStorage.setItem(draftKey(kind), JSON.stringify(draft));
    } else {
      window.sessionStorage.removeItem(draftKey(kind));
    }
  } catch {
    // не сохранили — черновик живёт, пока открыта страница
  }
}

/** Ошибка разбора JSON или пусто. */
export function jsonError(text: string): string | undefined {
  try {
    JSON.parse(text);
    return undefined;
  } catch (error) {
    return error instanceof Error ? error.message : String(error);
  }
}

const LOCAL = /^(\d{4}-\d{2}-\d{2})[ T](\d{2}:\d{2}(?::\d{2}(?:\.\d{1,9})?)?)$/;

/**
 * Момент «не раньше» из текста: ISO-8601 с зоной ({@code 2026-11-12T07:00:00Z}) или местное время
 * браузера ({@code 2026-11-12 10:00}). Пусто — {@code null}, не разобралось — {@code undefined}.
 */
export function parseMoment(text?: string): Date | null | undefined {
  const value = (text ?? '').trim();
  if (value === '') {
    return null;
  }
  const local = LOCAL.exec(value);
  const date = local ? new Date(`${local[1]}T${local[2]}`) : /[zZ]|[+-]\d{2}:?\d{2}$/.test(value) ? new Date(value) : undefined;
  return date && !Number.isNaN(date.getTime()) ? date : undefined;
}

export function Create() {
  const { t } = useI18n();
  const [search] = useSearchParams();
  const prefill: CreatePrefill = {
    executionId: search.get('executionId') ?? undefined,
    executionCreatedAt: search.get('executionCreatedAt') ?? undefined,
  };
  return (
    <Flex vertical gap={12}>
      <Typography.Title level={3}>{t('create.title')}</Typography.Title>
      <CreatePanel prefill={prefill} />
    </Flex>
  );
}

/** Вкладки создания — для страницы и для боковой панели. */
export function CreatePanel({ prefill = {}, onDone }: { prefill?: CreatePrefill; onDone?: () => void }) {
  const { t } = useI18n();
  const meta = useMeta();
  return (
    <Flex vertical gap={12}>
      <Alert type="warning" showIcon title={t('create.warning')} />
      <Loadable loading={meta.isLoading} error={meta.error}>
        {() => {
          const data = meta.data as Meta;
          const items = [
            ...(data.tasks ? [{ key: 'task', label: t('create.task') }] : []),
            ...(data.communications ? [{ key: 'outbound', label: t('create.communication') }] : []),
            ...(data.communications?.manualInbound ? [{ key: 'inbound', label: t('create.inbound') }] : []),
          ].map((item) => ({
            ...item,
            children: <CreateForm kind={item.key as Kind} meta={data} prefill={prefill} onDone={onDone} />,
          }));
          return <Tabs items={items} />;
        }}
      </Loadable>
    </Flex>
  );
}

function names(
  kind: Kind,
  meta: Meta,
): { value: string; title: string; description?: string | null; priority?: number; transport?: string }[] {
  return kind === 'task'
    ? (meta.tasks?.types ?? []).map((type) => ({
        value: type.type,
        title: type.title,
        description: type.description,
        priority: type.defaultPriority,
      }))
    : (meta.communications?.systems ?? []).map((system) => ({
        value: system.system,
        title: system.title,
        description: system.description,
        priority: system.defaultPriority,
        transport: system.transport,
      }));
}

function CreateForm({ kind, meta, prefill, onDone }: {
  kind: Kind;
  meta: Meta;
  prefill: CreatePrefill;
  onDone?: () => void;
}) {
  const { t } = useI18n();
  const navigate = useNavigate();
  const dark = useContext(DarkContext);
  const [form] = Form.useForm<Values>();
  const [draft] = useState(() => loadDraft(kind));
  const [payload, setPayload] = useState(draft?.payload ?? EMPTY_PAYLOAD);
  // Каждое изменение полей — новая версия черновика; сохраняется вместе с нагрузкой.
  const [version, changed] = useReducer((value: number) => value + 1, 0);
  const [error, setError] = useState<string | undefined>();
  const [duplicate, setDuplicate] = useState<string | undefined>();
  const options = names(kind, meta);
  const name = Form.useWatch('name', form);
  const selected = options.find((option) => option.value === name);
  const contentType = Form.useWatch('contentType', form) ?? 'application/json';
  const isJson = kind !== 'inbound' || contentType.includes('json');
  const parseError = isJson ? jsonError(payload) : undefined;

  useEffect(() => {
    saveDraft(kind, { values: form.getFieldsValue(true) as Values, payload });
  }, [kind, payload, version, form]);

  // «Создать в этом деле» меняет в черновике только дело.
  useEffect(() => {
    if (prefill.executionId) {
      form.setFieldValue('executionId', prefill.executionId);
      changed();
    }
  }, [prefill.executionId, form]);

  const clear = () => {
    form.setFieldsValue({
      name: undefined,
      priority: undefined,
      notBefore: undefined,
      executionId: undefined,
      dedupKey: undefined,
      correlationId: undefined,
      requestId: undefined,
      contentType: 'application/json',
    });
    setPayload(EMPTY_PAYLOAD);
    setError(undefined);
    setDuplicate(undefined);
    changed();
  };

  const create = useMutation({
    mutationFn: async (values: Values) => {
      const system = encodeURIComponent(values.name ?? '');
      if (kind === 'inbound') {
        return postJson<Created>(
          `/communications/inbound/${system}`,
          { requestId: values.requestId, dedupKey: values.dedupKey, correlationId: values.correlationId },
          payload,
          contentType,
        );
      }
      const executionId = values.executionId?.trim() || undefined;
      const common: Params = {
        priority: values.priority,
        notBefore: parseMoment(values.notBefore)?.toISOString(),
        executionId,
        executionCreatedAt: executionId && executionId === prefill.executionId ? prefill.executionCreatedAt : undefined,
      };
      return kind === 'task'
        ? postJson<Created>('/tasks', { type: values.name, ...common }, payload)
        : postJson<Created>(
            `/communications/outbound/${system}`,
            { ...common, dedupKey: values.dedupKey, correlationId: values.correlationId },
            payload,
          );
    },
    onSuccess: (response) => {
      const created = response.body;
      if (created.duplicateOf) {
        // Дубликат создан сразу SKIPPED — показываем совпавшую, а не уходим на пропущенную.
        setDuplicate(created.duplicateOf);
        return;
      }
      navigate(
        kind === 'task'
          ? taskPath('TASK', created.id, created.execution.createdAt)
          : communicationPath(created.id, created.execution.createdAt),
      );
      onDone?.();
    },
    onError: (failure) => {
      setError(failure instanceof ApiError && failure.forbidden ? t('common.forbidden') : failure.message);
    },
  });

  if (options.length === 0) {
    return <Alert type="info" title={t('create.noTypes')} />;
  }

  return (
    <Form<Values>
      form={form}
      name={`taskcomm-create-${kind}`}
      layout="vertical"
      autoComplete="off"
      style={{ maxWidth: 820 }}
      initialValues={{
        contentType: 'application/json',
        ...draft?.values,
        ...(prefill.executionId ? { executionId: prefill.executionId } : {}),
      }}
      onValuesChange={(values: Values) => {
        if (kind === 'inbound' && values.name) {
          const transport = options.find((option) => option.value === values.name)?.transport;
          form.setFieldValue('contentType', transport === 'SOAP' ? 'text/xml' : 'application/json');
        }
        changed();
      }}
      onFinish={(values) => {
        setError(undefined);
        setDuplicate(undefined);
        create.mutate(values);
      }}
    >
      <Form.Item
        name="name"
        label={kind === 'task' ? t('create.taskType') : kind === 'outbound' ? t('create.destination') : t('create.source')}
        rules={[{ required: true }]}
        extra={selected?.description}
      >
        <Select
          showSearch={{ optionFilterProp: 'label' }}
          options={options.map((option) => processorOption({ id: option.value, title: option.title }))}
        />
      </Form.Item>
      {kind === 'inbound' && (
        <Form.Item name="contentType" label={t('create.contentType')}>
          <Select
            style={{ maxWidth: 260 }}
            options={['application/json', 'text/xml', 'application/xml', 'text/plain'].map((value) => ({ value, label: value }))}
          />
        </Form.Item>
      )}
      <Form.Item
        label={kind === 'inbound' ? t('create.body') : t('common.payload')}
        required
        validateStatus={parseError || error ? 'error' : undefined}
        help={parseError ? t('create.invalidJson', { error: parseError }) : error}
      >
        {isJson ? (
          <Flex vertical gap={8}>
            <JsonEditor value={payload} onChange={setPayload} dark={dark} label={t('common.payload')} />
            <Button
              size="small"
              style={{ alignSelf: 'flex-start' }}
              disabled={parseError !== undefined}
              onClick={() => setPayload(JSON.stringify(JSON.parse(payload), null, 2))}
            >
              {t('create.format')}
            </Button>
          </Flex>
        ) : (
          <Input.TextArea
            autoComplete={NO_AUTOFILL}
            rows={10}
            value={payload}
            onChange={(event) => setPayload(event.target.value)}
            style={{ fontFamily: 'ui-monospace, Consolas, monospace' }}
          />
        )}
      </Form.Item>
      <Card size="small">
        <Space wrap size={16} align="start">
          {kind !== 'inbound' && (
            <>
              <Form.Item name="priority" label={t('common.priority')}>
                <InputNumber
                  min={1}
                  autoComplete={NO_AUTOFILL}
                  placeholder={selected?.priority !== undefined ? t('create.priorityDefault', { value: selected.priority }) : undefined}
                  style={{ width: 180 }}
                />
              </Form.Item>
              <Form.Item
                name="notBefore"
                label={t('create.notBefore')}
                extra={t('create.notBeforeHint')}
                rules={[
                  {
                    validator: async (_, value?: string) => {
                      if (parseMoment(value) === undefined) {
                        throw new Error(t('create.notBeforeInvalid'));
                      }
                    },
                  },
                ]}
              >
                <Input autoComplete={NO_AUTOFILL} allowClear placeholder="2026-11-12 10:00" style={{ width: 240 }} />
              </Form.Item>
            </>
          )}
          {kind === 'inbound' && (
            <Form.Item name="requestId" label={t('communications.requestId')}>
              <Input autoComplete={NO_AUTOFILL} />
            </Form.Item>
          )}
          {kind !== 'task' && (
            <>
              <Form.Item name="dedupKey" label={t('create.dedupKey')}>
                <Input autoComplete={NO_AUTOFILL} />
              </Form.Item>
              <Form.Item name="correlationId" label={t('create.correlationId')}>
                <Input autoComplete={NO_AUTOFILL} />
              </Form.Item>
            </>
          )}
        </Space>
        {kind !== 'inbound' && (
          <Form.Item name="executionId" label={t('create.executionId')} extra={t('create.executionHint')}>
            <Input autoComplete={NO_AUTOFILL} allowClear style={{ maxWidth: 380 }} />
          </Form.Item>
        )}
        {kind === 'inbound' && <Typography.Text type="secondary">{t('create.inboundHint')}</Typography.Text>}
      </Card>
      {prefill.executionId && kind !== 'inbound' && (
        <Tag style={{ marginTop: 12 }}>{t('create.inExecution', { id: shortId(prefill.executionId) })}</Tag>
      )}
      {duplicate && (
        <Alert
          type="info"
          showIcon
          style={{ marginTop: 12 }}
          title={<Link to={communicationPath(duplicate)}>{t('create.duplicate', { id: duplicate })}</Link>}
        />
      )}
      <Form.Item style={{ marginTop: 16 }}>
        <Space>
          <Button type="primary" htmlType="submit" loading={create.isPending} disabled={parseError !== undefined}>
            {t('create.submit')}
          </Button>
          <Button onClick={clear}>{t('create.clear')}</Button>
        </Space>
      </Form.Item>
    </Form>
  );
}
