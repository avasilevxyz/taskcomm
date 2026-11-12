import { useQuery } from '@tanstack/react-query';
import { Card, Col, Collapse, Descriptions, Empty, Flex, Row, Space, Table, Typography } from 'antd';
import { useParams, useSearchParams } from 'react-router';
import { getJson, type CommunicationAttempt, type CommunicationItem, type ExchangeMessage } from '../api';
import {
  executionPath,
  IdLink,
  JsonBlock,
  Loadable,
  OriginLink,
  OrphanTag,
  StatusTag,
  useTime,
} from '../common';
import { useI18n } from '../i18n';
import { Actions, canCancel } from './actions';

export function CommunicationCard() {
  const { id = '' } = useParams();
  const [search] = useSearchParams();
  const createdAt = search.get('createdAt') ?? undefined;
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
  const { t } = useI18n();
  const time = useTime();
  const createdAt = communication.execution.createdAt;
  const path = `/communications/${communication.id}`;
  const attempts = useQuery({
    queryKey: ['communications', 'attempts', communication.id],
    queryFn: () => getJson<CommunicationAttempt[]>(`${path}/attempts`, { executionCreatedAt: createdAt }),
  });
  const status = communication.state.status;
  return (
    <Flex vertical gap={16}>
      <Space align="baseline" wrap>
        <Typography.Title level={3} style={{ margin: 0 }}>
          {t(`direction.${communication.direction}`)} · {communication.externalSystem}
        </Typography.Title>
        <StatusTag status={status} description={communication.state.statusDescription} />
        <OrphanTag orphan={communication.state.orphan} />
      </Space>
      <Actions
        path={path}
        params={{ executionCreatedAt: createdAt }}
        cancellable={canCancel(status)}
        restartable={status === 'FAILED'}
      />
      <Card size="small">
        <Descriptions
          size="small"
          column={{ xs: 1, md: 2, xl: 3 }}
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
            { key: 'phase', label: t('common.phase'), children: communication.state.phase },
            { key: 'transport', label: t('communications.transport'), children: communication.state.contractShape ?? '—' },
            { key: 'priority', label: t('common.priority'), children: communication.state.priority },
            { key: 'created', label: t('common.created'), children: time(communication.state.createdAt) },
            { key: 'updated', label: t('common.updated'), children: time(communication.state.updatedAt) },
          ]}
        />
      </Card>
      <Card size="small" title={t('common.payload')}>
        <JsonBlock value={communication.requestPayload} />
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
              expandable={{
                defaultExpandedRowKeys: attempts.data?.length ? [attempts.data[attempts.data.length - 1].id] : [],
                expandedRowRender: (attempt) => <Exchange attempt={attempt} />,
              }}
              columns={[
                { title: t('common.phase'), dataIndex: 'phase' },
                {
                  title: t('common.status'),
                  render: (_, attempt) => (
                    <StatusTag status={attempt.outcome.status ?? 'RUNNING'} description={attempt.outcome.statusDescription} />
                  ),
                },
                { title: t('common.reason'), render: (_, attempt) => attempt.outcome.statusDescription ?? '—' },
                {
                  title: t('communications.httpStatus'),
                  render: (_, attempt) => attempt.exchange?.response?.status ?? '—',
                },
                { title: t('common.started'), render: (_, attempt) => time(attempt.times.startedAt) },
                { title: t('common.finished'), render: (_, attempt) => time(attempt.times.finishedAt) },
                { title: t('common.instance'), dataIndex: 'lockedBy', render: (value?: string) => value ?? '—' },
              ]}
            />
          )}
        </Loadable>
      </Card>
    </Flex>
  );
}

/** Что ушло и что вернулось в попытке, как есть, и доменные формы ответа и колбэка. */
function Exchange({ attempt }: { attempt: CommunicationAttempt }) {
  const { t } = useI18n();
  const exchange = attempt.exchange;
  if (!exchange || (!exchange.request && !exchange.response && !exchange.responsePayload && !exchange.callbackPayload)) {
    return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} />;
  }
  return (
    <Flex vertical gap={8}>
      <Row gutter={12}>
        <Col xs={24} lg={12}>
          <Message title={t('communications.request')} message={exchange.request} />
        </Col>
        <Col xs={24} lg={12}>
          <Message title={t('communications.response')} message={exchange.response} />
        </Col>
      </Row>
      {(exchange.responsePayload !== undefined || exchange.callbackPayload !== undefined) && (
        <Row gutter={12}>
          <Col xs={24} lg={12}>
            <Card size="small" title={t('common.result')}>
              <JsonBlock value={exchange.responsePayload} />
            </Card>
          </Col>
          <Col xs={24} lg={12}>
            <Card size="small" title={t('communications.callback')}>
              <JsonBlock value={exchange.callbackPayload} />
            </Card>
          </Col>
        </Row>
      )}
    </Flex>
  );
}

function Message({ title, message }: { title: string; message?: ExchangeMessage }) {
  const { t } = useI18n();
  const time = useTime();
  if (!message) {
    return (
      <Card size="small" title={title}>
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} />
      </Card>
    );
  }
  const headers = Object.entries(message.headers ?? {});
  return (
    <Card
      size="small"
      title={title}
      extra={
        <Space size={12}>
          {message.status !== undefined && <StatusTag status={String(message.status)} />}
          {message.at && <Typography.Text type="secondary">{time(message.at)}</Typography.Text>}
        </Space>
      }
    >
      {message.id && <Typography.Paragraph copyable>{message.id}</Typography.Paragraph>}
      {headers.length > 0 && (
        <Collapse
          size="small"
          items={[
            {
              key: 'headers',
              label: `${t('communications.headers')} (${headers.length})`,
              children: (
                <pre className="json">
                  {headers.map(([name, values]) => `${name}: ${values.join(', ')}`).join('\n')}
                </pre>
              ),
            },
          ]}
        />
      )}
      <Typography.Text type="secondary">{t('communications.body')}</Typography.Text>
      <JsonBlock value={message.body} />
    </Card>
  );
}
