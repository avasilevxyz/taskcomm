import { useQuery } from '@tanstack/react-query';
import { Card, Empty, Flex, Space, Table, Tabs, Tag, Typography } from 'antd';
import { Link, useParams, useSearchParams } from 'react-router';
import { getJson, type CommunicationAttempt, type CommunicationItem, type CommunicationKeys, type ExchangeMessage } from '../api';
import {
  executionPath,
  IdLink,
  JsonBlock,
  Loadable,
  OriginLink,
  OrphanTag,
  Outcome,
  PhaseLabel,
  ProcessorDescription,
  ProcessorId,
  ProcessorTitle,
  Reason,
  StatusTag,
  by,
  useAttemptSelection,
  useTime,
  Facts,
} from '../common';
import { CreateInExecutionButton } from '../createDrawer';
import { useI18n, type TranslationKey } from '../i18n';
import { useMeta } from '../meta';
import { Actions, canCancel } from './actions';

/** Карточка по адресу экрана. */
export function CommunicationCard() {
  const { id = '' } = useParams();
  const [search] = useSearchParams();
  return <CommunicationView id={id} createdAt={search.get('createdAt') ?? undefined} />;
}

/** Карточка по параметрам — на странице и в боковой панели. */
export function CommunicationView({ id, createdAt }: { id: string; createdAt?: string }) {
  const communication = useQuery({
    queryKey: ['communications', 'one', id, createdAt],
    queryFn: () =>
      getJson<CommunicationItem>(`/communications/${id}`, createdAt ? { executionCreatedAt: createdAt } : { everywhere: true }),
  });
  return (
    <Loadable loading={communication.isLoading} error={communication.error}>
      {() => (communication.data ? <CommunicationDetails communication={communication.data} /> : null)}
    </Loadable>
  );
}

function CommunicationDetails({ communication }: { communication: CommunicationItem }) {
  const { t, code } = useI18n();
  const time = useTime();
  const createdAt = communication.execution.createdAt;
  const path = `/communications/${communication.id}`;
  const attempts = useQuery({
    queryKey: ['communications', 'attempts', communication.id],
    queryFn: () => getJson<CommunicationAttempt[]>(`${path}/attempts`, { executionCreatedAt: createdAt }),
  });
  const status = communication.state.status;
  const keys = useQuery({
    queryKey: ['communications', 'keys', communication.id],
    queryFn: () => getJson<CommunicationKeys>(`${path}/keys`, { executionCreatedAt: createdAt }),
  });
  const meta = useMeta();
  const system = meta.data?.communications?.systems.find((item) => item.system === communication.externalSystem);
  const poller = system?.poller;
  const processing = latest(attempts.data, 'PROCESS');
  const { selected, rowSelection, onRow } = useAttemptSelection(attempts.data);
  return (
    <Flex vertical gap={16}>
      <Space align="baseline" wrap>
        <Typography.Title level={3} style={{ margin: 0 }}>
          {t(`direction.${communication.direction}`)} · {system?.title ?? communication.externalSystem}
        </Typography.Title>
        <ProcessorId kind="system" id={communication.externalSystem} />
        <StatusTag status={status} description={communication.state.statusDescription} />
        <OrphanTag orphan={communication.state.orphan} />
        <CreateInExecutionButton executionId={communication.execution.id} executionCreatedAt={createdAt} />
      </Space>
      <ProcessorDescription kind="system" id={communication.externalSystem} />
      <Actions
        path={path}
        params={{ executionCreatedAt: createdAt }}
        cancellable={canCancel(status)}
        restartable={status === 'FAILED'}
      />
      <Card size="small">
        <Facts
          items={[
            {
              key: 'id',
              label: t('common.id'),
              children: <Typography.Text copyable>{communication.id}</Typography.Text>,
            },
            {
              key: 'execution',
              label: t('common.execution'),
              children: <IdLink to={executionPath(communication.execution.id, createdAt)} id={communication.execution.id} />,
            },
            {
              key: 'origin',
              label: t('common.origin'),
              children: <OriginLink origin={communication.origin} executionCreatedAt={createdAt} />,
            },
            { key: 'reason', label: t('common.reason'), children: communication.state.statusDescription ?? '—' },
            { key: 'phase', label: t('common.phase'), children: <PhaseLabel phase={communication.state.phase} /> },
            { key: 'transport', label: t('communications.transport'), children: communication.state.contractShape ?? '—' },
            {
              key: 'requestId',
              label: t('communications.requestId'),
              children: <MessageId value={processing?.exchange?.request?.id} />,
            },
            {
              key: 'responseId',
              label: t('communications.responseId'),
              children: <MessageId value={processing?.exchange?.response?.id} />,
            },
            { key: 'priority', label: t('common.priority'), children: communication.state.priority },
            { key: 'poller', label: t('common.poller'), children: poller ?? '—' },
            { key: 'created', label: t('common.created'), children: time(communication.state.createdAt) },
            { key: 'updated', label: t('common.updated'), children: time(communication.state.updatedAt) },
          ]}
        />
      </Card>
      <Card size="small" title={t('common.payload')}>
        <JsonBlock value={communication.requestPayload} />
      </Card>
      <Card size="small" title={t('communications.keys')}>
        <Loadable loading={keys.isLoading} error={keys.error}>
          {() => <Keys communication={communication} keys={keys.data} />}
        </Loadable>
      </Card>
      <Card size="small" title={t('communications.exchange')}>
        <Loadable loading={attempts.isLoading} error={attempts.error}>
          {() => <Phases communication={communication} attempts={attempts.data ?? []} />}
        </Loadable>
      </Card>
      <Card size="small" title={t('common.attempts')}>
        <Loadable loading={attempts.isLoading} error={attempts.error}>
          {() => (
            <Table<CommunicationAttempt>
              size="small"
              scroll={{ x: 'max-content' }}
              rowKey="id"
              pagination={false}
              dataSource={attempts.data}
              rowSelection={rowSelection}
              onRow={onRow}
              columns={[
                { title: t('common.phase'), sorter: by((row: CommunicationAttempt) => row.phase), dataIndex: 'phase', render: (phase: string) => <PhaseLabel phase={phase} /> },
                {
                  title: t('common.status'),
                  sorter: by((row: CommunicationAttempt) => row.outcome.status),
                  render: (_, attempt) => (
                    <StatusTag status={attempt.outcome.status ?? 'RUNNING'} description={attempt.outcome.statusDescription} />
                  ),
                },
                { title: t('common.reason'), sorter: by((row: CommunicationAttempt) => row.outcome.statusDescription), render: (_, attempt) => <Reason text={attempt.outcome.statusDescription} /> },
                {
                  title: t('communications.requestId'),
                  sorter: by((row: CommunicationAttempt) => row.exchange?.request?.id),
                  render: (_, attempt) => <MessageId value={attempt.exchange?.request?.id} />,
                },
                {
                  title: t('communications.responseId'),
                  sorter: by((row: CommunicationAttempt) => row.exchange?.response?.id),
                  render: (_, attempt) => <MessageId value={attempt.exchange?.response?.id} />,
                },
                {
                  title: t('communications.httpStatus'),
                  sorter: by((row: CommunicationAttempt) => row.exchange?.response?.status),
                  render: (_, attempt) => attempt.exchange?.response?.status ?? '—',
                },
                { title: t('common.started'), sorter: by((row: CommunicationAttempt) => row.times.startedAt), render: (_, attempt) => time(attempt.times.startedAt) },
                { title: t('common.finished'), sorter: by((row: CommunicationAttempt) => row.times.finishedAt), render: (_, attempt) => time(attempt.times.finishedAt) },
                { title: t('common.instance'), sorter: by((row: CommunicationAttempt) => row.lockedBy), dataIndex: 'lockedBy', render: (value?: string) => value ?? '—' },
              ]}
            />
          )}
        </Loadable>
      </Card>
      {selected && (
        <Card
          size="small"
          title={t('communications.attemptExchange', { phase: code('phase', selected.phase), started: time(selected.times.startedAt) })}
        >
          <Flex vertical gap={12}>
            <Outcome outcome={selected.outcome} />
            <Exchange communication={communication} attempt={selected} />
          </Flex>
        </Card>
      )}
    </Flex>
  );
}

/**
 * Ключи коммуникации со ссылками на поиск по ним. Ключ дедупликации ищется в пространстве самой
 * коммуникации — направление и система; ключ корреляции — по своей системе.
 */
function Keys({ communication, keys }: { communication: CommunicationItem; keys?: CommunicationKeys }) {
  const { t } = useI18n();
  const dedup = keys?.dedup ?? [];
  const correlation = keys?.correlation ?? [];
  const dedupLink = (key: string, subject?: string | null) =>
    `/communications?${new URLSearchParams({
      direction: communication.direction,
      system: communication.externalSystem,
      dedupKey: key,
      ...(subject ? { dedupSubject: subject } : {}),
    })}`;
  const correlationLink = (system: string, id: string) =>
    `/communications?${new URLSearchParams({ correlationSystem: system, correlationId: id })}`;
  return (
    <Facts
      max={1}
      items={[
        {
          key: 'dedup',
          label: t('communications.dedupKeys'),
          children:
            dedup.length === 0 ? (
              <Typography.Text type="secondary">{t('communications.noKeys')}</Typography.Text>
            ) : (
              <Flex vertical gap={4}>
                {dedup.map((item) => (
                  <Space key={`${item.subject ?? ''}#${item.key}`} size={8} wrap>
                    {item.subject && <Tag>{t('communications.dedupSubject', { subject: item.subject })}</Tag>}
                    <Link to={dedupLink(item.key, item.subject)}>
                      <Typography.Text code>{item.key}</Typography.Text>
                    </Link>
                  </Space>
                ))}
              </Flex>
            ),
        },
        {
          key: 'correlation',
          label: t('communications.correlationKeys'),
          children:
            correlation.length === 0 ? (
              <Typography.Text type="secondary">{t('communications.noKeys')}</Typography.Text>
            ) : (
              <Flex vertical gap={4}>
                {correlation.map((item) => (
                  <Space key={`${item.externalSystem}#${item.correlationId}`} size={8} wrap>
                    <Tag>
                      <ProcessorTitle kind="system" id={item.externalSystem} />
                    </Tag>
                    <Link to={correlationLink(item.externalSystem, item.correlationId)}>
                      <Typography.Text code>{item.correlationId}</Typography.Text>
                    </Link>
                  </Space>
                ))}
              </Flex>
            ),
        },
      ]}
    />
  );
}

/** Последняя попытка фазы, у которой есть обмен. */
function latest(attempts: CommunicationAttempt[] | undefined, phase: string): CommunicationAttempt | undefined {
  return [...(attempts ?? [])].reverse().find((attempt) => attempt.phase === phase && attempt.exchange);
}

function MessageId({ value }: { value?: string }) {
  return value ? <Typography.Text copyable>{value}</Typography.Text> : <Typography.Text type="secondary">—</Typography.Text>;
}

/** Обмен по фазам: обработка и асинхронный ответ — у каждой последняя попытка. */
function Phases({ communication, attempts }: { communication: CommunicationItem; attempts: CommunicationAttempt[] }) {
  const { code } = useI18n();
  const items = (['PROCESS', 'HANDLE_RESPONSE'] as const)
    .map((phase) => ({ phase, attempt: latest(attempts, phase) }))
    .filter((item) => item.attempt !== undefined)
    .map(({ phase, attempt }) => ({
      key: phase,
      label: code('phase', phase),
      children: <Exchange communication={communication} attempt={attempt as CommunicationAttempt} />,
    }));
  if (items.length === 0) {
    return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} />;
  }
  return <Tabs size="small" items={items} />;
}

/** Подписи сторон обмена — по направлению коммуникации и фазе попытки. */
function sides(communication: CommunicationItem, attempt: CommunicationAttempt): [TranslationKey, TranslationKey] {
  if (attempt.phase === 'HANDLE_RESPONSE') {
    return ['communications.callbackReceived', 'communications.response'];
  }
  return communication.direction === 'OUTBOUND'
    ? ['communications.sent', 'communications.answered']
    : ['communications.received', 'communications.replied'];
}

/** Что ушло и что вернулось в попытке, как есть, и доменные формы ответа и колбэка. */
function Exchange({ communication, attempt }: { communication: CommunicationItem; attempt: CommunicationAttempt }) {
  const { t } = useI18n();
  const exchange = attempt.exchange;
  if (!exchange || (!exchange.request && !exchange.response && !exchange.responsePayload && !exchange.callbackPayload)) {
    return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} />;
  }
  const [request, response] = sides(communication, attempt);
  return (
    <Flex vertical gap={8}>
      {/* Две стороны рядом, только если обеим хватает места в своём блоке — на странице, не в панели. */}
      <div className="sides">
        <Message title={t(request)} message={exchange.request} />
        <Message title={t(response)} message={exchange.response} />
      </div>
      {(exchange.responsePayload !== undefined || exchange.callbackPayload !== undefined) && (
        <div className="sides">
          <Card size="small" title={t('common.result')}>
            <JsonBlock value={exchange.responsePayload} />
          </Card>
          <Card size="small" title={t('communications.callback')}>
            <JsonBlock value={exchange.callbackPayload} />
          </Card>
        </div>
      )}
    </Flex>
  );
}

/** Сообщение обмена: идентификатор, время, статус, заголовки и тело — всё подписано. */
function Message({ title, message }: { title: string; message?: ExchangeMessage }) {
  const { t } = useI18n();
  const time = useTime();
  if (!message) {
    return (
      <Card size="small" title={title}>
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('communications.notRecorded')} />
      </Card>
    );
  }
  const headers = Object.entries(message.headers ?? {}).flatMap(([name, values]) =>
    values.map((value, index) => ({ key: `${name}#${index}`, name, value })),
  );
  return (
    <Card size="small" title={title}>
      <Flex vertical gap={8}>
        <Facts
          max={1}
          items={[
            { key: 'id', label: t('communications.messageId'), children: <MessageId value={message.id} /> },
            { key: 'at', label: t('communications.at'), children: time(message.at) },
            ...(message.status !== undefined
              ? [{ key: 'status', label: t('communications.httpStatus'), children: <HttpStatus status={message.status} /> }]
              : []),
          ]}
        />
        <Typography.Text type="secondary">{t('communications.headers')}</Typography.Text>
        {headers.length === 0 ? (
          <Typography.Text type="secondary">{t('communications.noHeaders')}</Typography.Text>
        ) : (
          <Table
            size="small"
            pagination={false}
            showHeader={false}
            rowKey="key"
            dataSource={headers}
            columns={[
              { dataIndex: 'name', render: (name: string) => <Typography.Text code>{name}</Typography.Text> },
              { dataIndex: 'value', render: (value: string) => <Typography.Text copyable>{value}</Typography.Text> },
            ]}
          />
        )}
        <Typography.Text type="secondary">{t('communications.body')}</Typography.Text>
        <JsonBlock value={message.body} />
      </Flex>
    </Card>
  );
}

function HttpStatus({ status }: { status: number }) {
  return <Tag color={status >= 500 ? 'error' : status >= 400 ? 'warning' : 'success'}>{status}</Tag>;
}
