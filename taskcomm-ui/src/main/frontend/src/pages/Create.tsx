import { useMutation } from '@tanstack/react-query';
import { Alert, Button, Card, Flex, Form, Input, InputNumber, Select, Space, Tabs, Typography } from 'antd';
import { useContext, useState } from 'react';
import { Link, useNavigate } from 'react-router';
import { ApiError, postJson, type Created, type Params } from '../api';
import { DarkContext, useMeta } from '../meta';
import { communicationPath, Loadable, taskPath } from '../common';
import { useI18n } from '../i18n';
import { JsonEditor } from '../JsonEditor';

/** Поля формы, общие для задачи и коммуникации. */
interface Common {
  priority?: number;
  notBefore?: string;
  executionId?: string;
  executionCreatedAt?: string;
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

function commonParams(values: Common): Params {
  return {
    priority: values.priority,
    notBefore: values.notBefore ? new Date(values.notBefore).toISOString() : undefined,
    executionId: values.executionId || undefined,
    executionCreatedAt: values.executionCreatedAt || undefined,
  };
}

export function Create() {
  const { t } = useI18n();
  const meta = useMeta();
  return (
    <Flex vertical gap={12}>
      <Typography.Title level={3}>{t('create.title')}</Typography.Title>
      <Alert type="warning" showIcon title={t('create.warning')} />
      <Loadable loading={meta.isLoading} error={meta.error}>
        {() => (
          <Tabs
            items={[
              ...(meta.data?.tasks
                ? [{ key: 'task', label: t('create.task'), children: <CreateForm kind="task" names={meta.data.tasks.types.map((item) => item.type)} /> }]
                : []),
              ...(meta.data?.communications
                ? [
                    {
                      key: 'communication',
                      label: t('create.communication'),
                      children: (
                        <CreateForm kind="communication" names={meta.data.communications.systems.map((item) => item.system)} />
                      ),
                    },
                  ]
                : []),
            ]}
          />
        )}
      </Loadable>
    </Flex>
  );
}

function CreateForm({ kind, names }: { kind: 'task' | 'communication'; names: string[] }) {
  const { t } = useI18n();
  const navigate = useNavigate();
  const dark = useContext(DarkContext);
  const [form] = Form.useForm<Common & { name?: string; dedupKey?: string; correlationId?: string }>();
  const [payload, setPayload] = useState('{\n  \n}');
  const [error, setError] = useState<string | undefined>();
  const [duplicate, setDuplicate] = useState<string | undefined>();
  const parseError = jsonError(payload);

  const create = useMutation({
    mutationFn: async (values: Common & { name?: string; dedupKey?: string; correlationId?: string }) => {
      const name = values.name ?? '';
      return kind === 'task'
        ? postJson<Created>('/tasks', { type: name, ...commonParams(values) }, payload)
        : postJson<Created>(
            `/communications/outbound/${encodeURIComponent(name)}`,
            { ...commonParams(values), dedupKey: values.dedupKey, correlationId: values.correlationId },
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
    },
    onError: (failure) => {
      setError(failure instanceof ApiError && failure.forbidden ? t('common.forbidden') : failure.message);
    },
  });

  if (names.length === 0) {
    return <Alert type="info" title={t('create.noTypes')} />;
  }

  return (
    <Form
      form={form}
      layout="vertical"
      style={{ maxWidth: 820 }}
      onFinish={(values) => {
        setError(undefined);
        setDuplicate(undefined);
        create.mutate(values);
      }}
    >
      <Form.Item
        name="name"
        label={kind === 'task' ? t('create.taskType') : t('create.destination')}
        rules={[{ required: true }]}
      >
        <Select showSearch options={names.map((name) => ({ value: name, label: name }))} />
      </Form.Item>
      <Form.Item
        label={t('common.payload')}
        required
        validateStatus={parseError || error ? 'error' : undefined}
        help={parseError ? t('create.invalidJson', { error: parseError }) : error}
      >
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
      </Form.Item>
      <Card size="small">
        <Space wrap size={16} align="start">
          <Form.Item name="priority" label={t('common.priority')}>
            <InputNumber min={1} />
          </Form.Item>
          <Form.Item name="notBefore" label={t('create.notBefore')}>
            <Input type="datetime-local" />
          </Form.Item>
          {kind === 'communication' && (
            <>
              <Form.Item name="dedupKey" label={t('create.dedupKey')}>
                <Input />
              </Form.Item>
              <Form.Item name="correlationId" label={t('create.correlationId')}>
                <Input />
              </Form.Item>
            </>
          )}
        </Space>
        <Space wrap size={16} align="start">
          <Form.Item name="executionId" label={t('create.executionId')} extra={t('create.executionHint')}>
            <Input style={{ width: 340 }} />
          </Form.Item>
          <Form.Item name="executionCreatedAt" label={t('create.executionCreatedAt')}>
            <Input style={{ width: 280 }} placeholder="2026-11-12T10:00:00.123456Z" />
          </Form.Item>
        </Space>
      </Card>
      {duplicate && (
        <Alert
          type="info"
          showIcon
          title={<Link to={communicationPath(duplicate)}>{t('create.duplicate', { id: duplicate })}</Link>}
        />
      )}
      <Form.Item style={{ marginTop: 16 }}>
        <Button type="primary" htmlType="submit" loading={create.isPending} disabled={parseError !== undefined}>
          {t('create.submit')}
        </Button>
      </Form.Item>
    </Form>
  );
}
