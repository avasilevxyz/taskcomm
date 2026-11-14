import { useQuery } from '@tanstack/react-query';
import { Alert, Card, Flex, Space, Tag, Typography } from 'antd';
import { Link } from 'react-router';
import { getJson, type CircuitBreaker, type CommunicationSummary, type TaskTypeSummary } from '../api';
import { useMeta } from '../meta';
import { by, Loadable, OrphanTag, ProcessorTitle, StatusTag, useTime } from '../common';
import { useWindowChoice, windowParams } from '../windowChoice';
import { DataTable } from '../dataTable';
import { useI18n } from '../i18n';

/** Строка сводки: тип на уровне — и сколько в каждом статусе. */
interface Row<TKey> {
  key: string;
  group: TKey;
  name: string;
  orphan: boolean;
  counts: Record<string, number>;
}

export function pivot<TGroup extends string>(
  rows: { group: TGroup; name: string; status: string; count: number; orphan: boolean }[],
): Row<TGroup>[] {
  const byKey = new Map<string, Row<TGroup>>();
  for (const row of rows) {
    const key = `${row.group}/${row.name}`;
    const existing = byKey.get(key) ?? { key, group: row.group, name: row.name, orphan: row.orphan, counts: {} };
    existing.counts[row.status] = (existing.counts[row.status] ?? 0) + row.count;
    existing.orphan = existing.orphan || row.orphan;
    byKey.set(key, existing);
  }
  return [...byKey.values()].sort(
    (a, b) => rank(a.group) - rank(b.group) || a.group.localeCompare(b.group) || a.name.localeCompare(b.name),
  );
}

/** Уровни — в порядке иерархии, а не по алфавиту. */
const ORDER = ['TASK', 'BATCH', 'UNIT', 'INBOUND', 'OUTBOUND'];

function rank(group: string): number {
  const index = ORDER.indexOf(group);
  return index < 0 ? ORDER.length : index;
}

function Counts({ counts, link }: { counts: Record<string, number>; link: (status: string) => string }) {
  return (
    <Space size={[4, 4]} wrap>
      {Object.entries(counts)
        .sort(([a], [b]) => a.localeCompare(b))
        .map(([status, count]) => (
          <Link key={status} to={link(status)}>
            <Space size={2}>
              <StatusTag status={status} />
              <Typography.Text>{count}</Typography.Text>
            </Space>
          </Link>
        ))}
    </Space>
  );
}

export function Overview() {
  const { t } = useI18n();
  const meta = useMeta();
  const [choice] = useWindowChoice();
  const params = () => windowParams(choice);
  const tasks = useQuery({
    queryKey: ['tasks', 'summary', choice],
    queryFn: () => getJson<TaskTypeSummary[]>('/tasks/summary', params()),
    enabled: meta.data?.tasks !== undefined,
  });
  const communications = useQuery({
    queryKey: ['communications', 'summary', choice],
    queryFn: () => getJson<CommunicationSummary[]>('/communications/summary', params()),
    enabled: meta.data?.communications !== undefined,
  });

  return (
    <Loadable loading={meta.isLoading} error={meta.error}>
      {() => (
        <Flex vertical gap={16}>
          <Card title={t('overview.tasks')} size="small">
            {meta.data?.tasks === undefined ? (
              <Typography.Text type="secondary">{t('overview.disabled')}</Typography.Text>
            ) : (
              <Loadable loading={tasks.isLoading} error={tasks.error}>
                {() => (
                  <DataTable
                    size="small"
                    scroll={{ x: 'max-content' }}
                    pagination={false}
                    dataSource={pivot(
                      (tasks.data ?? []).map((row) => ({ ...row, group: row.level, name: row.taskType })),
                    )}
                    columns={[
                      { title: t('overview.level'), sorter: by((row: { group: string; name: string; counts: Record<string, number> }) => rank(row.group)), dataIndex: 'group', render: (level: string) => t(`level.${level as 'TASK'}`) },
                      {
                        title: t('common.type'),
                        sorter: by((row: { group: string; name: string; counts: Record<string, number> }) => row.name),
                        render: (_, row) => (
                          <Space>
                            <Link to={`/tasks?level=${row.group}&type=${encodeURIComponent(row.name)}`}>
                              <ProcessorTitle kind="task" id={row.name} />
                            </Link>
                            <OrphanTag orphan={row.orphan} />
                          </Space>
                        ),
                      },
                      {
                        title: t('common.status'),
                        sorter: by((row: { group: string; name: string; counts: Record<string, number> }) => Object.values(row.counts).reduce((sum, count) => sum + count, 0)),
                        render: (_, row) => (
                          <Counts
                            counts={row.counts}
                            link={(status) =>
                              `/tasks?level=${row.group}&type=${encodeURIComponent(row.name)}&status=${status}`
                            }
                          />
                        ),
                      },
                    ]}
                  />
                )}
              </Loadable>
            )}
          </Card>
          <Card title={t('overview.communications')} size="small">
            {meta.data?.communications === undefined ? (
              <Typography.Text type="secondary">{t('overview.disabled')}</Typography.Text>
            ) : (
              <Loadable loading={communications.isLoading} error={communications.error}>
                {() => (
                  <DataTable
                    size="small"
                    scroll={{ x: 'max-content' }}
                    pagination={false}
                    dataSource={pivot(
                      (communications.data ?? []).map((row) => ({
                        ...row,
                        group: row.direction,
                        name: row.externalSystem,
                      })),
                    )}
                    columns={[
                      {
                        title: t('communications.direction'),
                        sorter: by((row: { group: string; name: string; counts: Record<string, number> }) => row.group),
                        dataIndex: 'group',
                        render: (direction: string) => t(`direction.${direction as 'INBOUND'}`),
                      },
                      {
                        title: t('communications.system'),
                        sorter: by((row: { group: string; name: string; counts: Record<string, number> }) => row.name),
                        render: (_, row) => (
                          <Space>
                            <Link
                              to={`/communications?direction=${row.group}&system=${encodeURIComponent(row.name)}`}
                            >
                              <ProcessorTitle kind="system" id={row.name} />
                            </Link>
                            <OrphanTag orphan={row.orphan} />
                          </Space>
                        ),
                      },
                      {
                        title: t('common.status'),
                        sorter: by((row: { group: string; name: string; counts: Record<string, number> }) => Object.values(row.counts).reduce((sum, count) => sum + count, 0)),
                        render: (_, row) => (
                          <Counts
                            counts={row.counts}
                            link={(status) =>
                              `/communications?direction=${row.group}&system=${encodeURIComponent(row.name)}&status=${status}`
                            }
                          />
                        ),
                      },
                    ]}
                  />
                )}
              </Loadable>
            )}
          </Card>
          {meta.data?.communications !== undefined && <Breakers />}
        </Flex>
      )}
    </Loadable>
  );
}

function Breakers() {
  const { t } = useI18n();
  const time = useTime();
  const breakers = useQuery({
    queryKey: ['communications', 'circuit-breakers'],
    queryFn: () => getJson<CircuitBreaker[]>('/communications/circuit-breakers'),
  });
  const instance = breakers.data?.[0]?.instance;
  return (
    <Card title={instance ? t('overview.breakersOf', { instance }) : t('overview.breakers')} size="small">
      <Loadable loading={breakers.isLoading} error={breakers.error}>
        {() =>
          (breakers.data ?? []).length === 0 ? (
            <Alert type="info" title={t('overview.breakersEmpty')} />
          ) : (
            <DataTable
              size="small"
              scroll={{ x: 'max-content' }}
              pagination={false}
              rowKey="externalSystem"
              dataSource={breakers.data}
              columns={[
                { title: t('communications.system'), sorter: by((row: CircuitBreaker) => row.externalSystem), dataIndex: 'externalSystem', render: (system: string) => <ProcessorTitle kind="system" id={system} /> },
                { title: t('overview.state'), sorter: by((row: CircuitBreaker) => row.state), dataIndex: 'state', render: (state: string) => <StatusTag status={state} /> },
                { title: t('overview.calls'), sorter: by((row: CircuitBreaker) => row.calls), dataIndex: 'calls' },
                {
                  title: t('overview.failureRate'),
                  sorter: by((row: CircuitBreaker) => row.failureRate),
                  dataIndex: 'failureRate',
                  render: (rate: number) => <Tag>{Math.round(rate * 100)}%</Tag>,
                },
                { title: t('overview.nextProbe'), sorter: by((row: CircuitBreaker) => row.nextProbeAt), dataIndex: 'nextProbeAt', render: (at?: string | null) => time(at) },
              ]}
            />
          )
        }
      </Loadable>
    </Card>
  );
}
