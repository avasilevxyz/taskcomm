import { useQuery } from '@tanstack/react-query';
import { Card, Descriptions, Flex, Space, Table, Tabs, Typography } from 'antd';
import { Link, useParams, useSearchParams } from 'react-router';
import { getJson, type Level, type TaskAttempt, type TaskItem } from '../api';
import {
  executionPath,
  IdLink,
  JsonBlock,
  Loadable,
  OriginLink,
  OrphanTag,
  StatusTag,
  taskPath,
  useTime,
} from '../common';
import { useI18n } from '../i18n';
import { Actions, canCancel, canRestart } from './actions';
import { TaskTable, useTaskPages } from './TaskTable';

export function TaskCard() {
  const { level: levelParam = 'TASK', id = '' } = useParams();
  const level = (['TASK', 'BATCH', 'UNIT'].includes(levelParam) ? levelParam : 'TASK') as Level;
  const [search] = useSearchParams();
  const createdAt = search.get('createdAt') ?? undefined;
  const params = createdAt ? { executionCreatedAt: createdAt } : { everywhere: true };
  const task = useQuery({
    queryKey: ['tasks', 'one', level, id, createdAt],
    queryFn: () => getJson<TaskItem>(`/tasks/${level}/${id}`, params),
  });
  return (
    <Loadable loading={task.isLoading} error={task.error}>
      {() => (task.data ? <TaskDetails task={task.data} /> : null)}
    </Loadable>
  );
}

function TaskDetails({ task }: { task: TaskItem }) {
  const { t } = useI18n();
  const time = useTime();
  const createdAt = task.execution.createdAt;
  const path = `/tasks/${task.level}/${task.id}`;
  const attempts = useQuery({
    queryKey: ['tasks', 'attempts', task.level, task.id],
    queryFn: () => getJson<TaskAttempt[]>(`${path}/attempts`, { executionCreatedAt: createdAt }),
  });
  return (
    <Flex vertical gap={16}>
      <Space align="baseline" wrap>
        <Typography.Title level={3} style={{ margin: 0 }}>
          {t(`level.${task.level}`)} {task.taskType}
        </Typography.Title>
        <StatusTag status={task.state.status} description={task.state.statusDescription} />
        <OrphanTag orphan={task.state.orphan} />
      </Space>
      <Actions
        path={path}
        params={{ executionCreatedAt: createdAt }}
        cancellable={canCancel(task.state.status)}
        restartable={canRestart(task.state.status)}
      />
      <Card size="small">
        <Descriptions
          size="small"
          column={{ xs: 1, md: 2, xl: 3 }}
          items={[
            { key: 'id', label: t('common.id'), children: <Typography.Text copyable>{task.id}</Typography.Text> },
            {
              key: 'execution',
              label: t('common.execution'),
              children: <IdLink to={executionPath(task.execution.id, createdAt)} id={task.execution.id} />,
            },
            {
              key: 'parent',
              label: t('tasks.parent'),
              children: task.parents?.batchId ? (
                <IdLink to={taskPath('BATCH', task.parents.batchId, createdAt)} id={task.parents.batchId} />
              ) : task.parents?.taskId ? (
                <IdLink to={taskPath('TASK', task.parents.taskId, createdAt)} id={task.parents.taskId} />
              ) : (
                '—'
              ),
            },
            { key: 'origin', label: t('common.origin'), children: <OriginLink origin={task.origin} executionCreatedAt={createdAt} /> },
            { key: 'reason', label: t('common.reason'), children: task.state.statusDescription ?? '—' },
            { key: 'phase', label: t('common.phase'), children: task.state.phase ?? '—' },
            { key: 'priority', label: t('common.priority'), children: task.state.priority },
            { key: 'created', label: t('common.created'), children: time(task.state.createdAt) },
            { key: 'updated', label: t('common.updated'), children: time(task.state.updatedAt) },
          ]}
        />
      </Card>
      <Card size="small" title={t('common.payload')}>
        <JsonBlock value={task.payload} />
      </Card>
      <Card size="small" title={t('common.attempts')}>
        <Loadable loading={attempts.isLoading} error={attempts.error}>
          {() => (
            <Table<TaskAttempt>
              size="small"
              rowKey="id"
              pagination={false}
              dataSource={attempts.data}
              expandable={{
                rowExpandable: (attempt) => attempt.result !== undefined,
                expandedRowRender: (attempt) => <JsonBlock value={attempt.result} />,
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
                { title: t('common.started'), render: (_, attempt) => time(attempt.times.startedAt) },
                { title: t('common.finished'), render: (_, attempt) => time(attempt.times.finishedAt) },
                { title: t('common.instance'), dataIndex: 'lockedBy', render: (value?: string) => value ?? '—' },
                { title: t('common.leaseRenewals'), dataIndex: 'leaseRenewals' },
              ]}
            />
          )}
        </Loadable>
      </Card>
      {task.level !== 'UNIT' && <Children task={task} />}
      <Link to={`/tasks?level=${task.level}&executionId=${task.execution.id}&executionCreatedAt=${encodeURIComponent(createdAt)}`}>
        {t('tasks.inExecution', { id: task.execution.id })}
      </Link>
    </Flex>
  );
}

function Children({ task }: { task: TaskItem }) {
  const { t } = useI18n();
  const scope = { executionId: task.execution.id, executionCreatedAt: task.execution.createdAt, parentId: task.id };
  const batches = useTaskPages(
    ['children', 'BATCH', task.id],
    () => ({ ...scope, level: 'BATCH' }),
    task.level === 'TASK',
  );
  const units = useTaskPages(['children', 'UNIT', task.id], () => ({ ...scope, level: 'UNIT' }));
  const tabs = [
    ...(task.level === 'TASK'
      ? [{ key: 'batches', label: t('tasks.batches'), children: <TaskTable pages={batches} showWindow={false} /> }]
      : []),
    { key: 'units', label: t('tasks.units'), children: <TaskTable pages={units} showWindow={false} /> },
  ];
  return (
    <Card size="small" title={t('tasks.children')}>
      <Tabs items={tabs} />
    </Card>
  );
}
