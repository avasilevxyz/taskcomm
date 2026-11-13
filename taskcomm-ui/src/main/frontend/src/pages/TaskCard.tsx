import { useQuery } from '@tanstack/react-query';
import { Card, Flex, Space, Table, Tabs, Typography } from 'antd';
import { Link, useParams, useSearchParams } from 'react-router';
import { getJson, type Level, type TaskAttempt, type TaskItem } from '../api';
import {
  executionPath,
  IdLink,
  JsonBlock,
  Loadable,
  OriginLink,
  CountLabel,
  OrphanTag,
  Outcome,
  PhaseLabel,
  ProcessorDescription,
  ProcessorId,
  Reason,
  StatusTag,
  by,
  taskPath,
  useAttemptSelection,
  useTime,
  Facts,
} from '../common';
import { CreateInExecutionButton } from '../createDrawer';
import { useI18n } from '../i18n';
import { useMeta } from '../meta';
import { Actions, canCancel, canRestart } from './actions';
import { TaskTable, useTaskPages } from './TaskTable';

/** Карточка по адресу экрана. */
export function TaskCard() {
  const { level: levelParam = 'TASK', id = '' } = useParams();
  const level = (['TASK', 'BATCH', 'UNIT'].includes(levelParam) ? levelParam : 'TASK') as Level;
  const [search] = useSearchParams();
  return <TaskView level={level} id={id} createdAt={search.get('createdAt') ?? undefined} />;
}

/** Карточка по параметрам — на странице и в боковой панели. */
export function TaskView({ level, id, createdAt }: { level: Level; id: string; createdAt?: string }) {
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
  const { t, code } = useI18n();
  const time = useTime();
  const createdAt = task.execution.createdAt;
  const path = `/tasks/${task.level}/${task.id}`;
  const meta = useMeta();
  const taskType = meta.data?.tasks?.types.find((type) => type.type === task.taskType);
  const contract = taskType?.contract;
  const attempts = useQuery({
    queryKey: ['tasks', 'attempts', task.level, task.id],
    queryFn: () => getJson<TaskAttempt[]>(`${path}/attempts`, { executionCreatedAt: createdAt }),
  });
  const { selected, rowSelection, onRow } = useAttemptSelection(attempts.data);
  return (
    <Flex vertical gap={16}>
      <Space align="baseline" wrap>
        <Typography.Title level={3} style={{ margin: 0 }}>
          {t(`level.${task.level}`)} {taskType?.title ?? task.taskType}
        </Typography.Title>
        <ProcessorId kind="task" id={task.taskType} />
        <StatusTag status={task.state.status} description={task.state.statusDescription} />
        <OrphanTag orphan={task.state.orphan} />
        <CreateInExecutionButton executionId={task.execution.id} executionCreatedAt={createdAt} />
      </Space>
      <ProcessorDescription kind="task" id={task.taskType} />
      <Actions
        path={path}
        params={{ executionCreatedAt: createdAt }}
        cancellable={canCancel(task.state.status)}
        restartable={canRestart(task.state.status)}
      />
      <Card size="small">
        <Facts
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
            { key: 'phase', label: t('common.phase'), children: <PhaseLabel phase={task.state.phase} /> },
            { key: 'priority', label: t('common.priority'), children: task.state.priority },
            { key: 'poller', label: t('common.poller'), children: taskType?.poller ?? '—' },
            { key: 'created', label: t('common.created'), children: time(task.state.createdAt) },
            { key: 'updated', label: t('common.updated'), children: time(task.state.updatedAt) },
          ]}
        />
      </Card>
      <Card size="small" title={t('common.payload')}>
        <JsonBlock value={task.payload} />
      </Card>
      <LastResult attempts={attempts.data} loading={attempts.isLoading} error={attempts.error} />
      <Card size="small" title={t('common.attempts')}>
        <Loadable loading={attempts.isLoading} error={attempts.error}>
          {() => (
            <Table<TaskAttempt>
              size="small"
              scroll={{ x: 'max-content' }}
              rowKey="id"
              pagination={false}
              dataSource={attempts.data}
              rowSelection={rowSelection}
              onRow={onRow}
              columns={[
                { title: t('common.phase'), sorter: by((row: TaskAttempt) => row.phase), dataIndex: 'phase', render: (phase: string) => <PhaseLabel phase={phase} /> },
                {
                  title: t('common.status'),
                  sorter: by((row: TaskAttempt) => row.outcome.status),
                  render: (_, attempt) => (
                    <StatusTag status={attempt.outcome.status ?? 'RUNNING'} description={attempt.outcome.statusDescription} />
                  ),
                },
                { title: t('common.reason'), sorter: by((row: TaskAttempt) => row.outcome.statusDescription), render: (_, attempt) => <Reason text={attempt.outcome.statusDescription} /> },
                { title: t('common.started'), sorter: by((row: TaskAttempt) => row.times.startedAt), render: (_, attempt) => time(attempt.times.startedAt) },
                { title: t('common.finished'), sorter: by((row: TaskAttempt) => row.times.finishedAt), render: (_, attempt) => time(attempt.times.finishedAt) },
                { title: t('common.instance'), sorter: by((row: TaskAttempt) => row.lockedBy), dataIndex: 'lockedBy', render: (value?: string) => value ?? '—' },
                { title: t('common.leaseRenewals'), sorter: by((row: TaskAttempt) => row.leaseRenewals), dataIndex: 'leaseRenewals' },
              ]}
            />
          )}
        </Loadable>
      </Card>
      {selected && (
        <Card
          size="small"
          title={t('tasks.selectedAttempt', { phase: code('phase', selected.phase), started: time(selected.times.startedAt) })}
        >
          <Flex vertical gap={12}>
            <Outcome outcome={selected.outcome} />
            <AttemptDetails key={selected.id} task={task} contract={contract} attempt={selected} />
          </Flex>
        </Card>
      )}
      <Link to={`/tasks?level=${task.level}&executionId=${task.execution.id}&executionCreatedAt=${encodeURIComponent(createdAt)}`}>
        {t('tasks.inExecution', { id: task.execution.id })}
      </Link>
    </Flex>
  );
}

/** Что оставила попытка: раскрытие — своих детей, обработка и сборка — результат. */
function AttemptDetails({ task, contract, attempt }: { task: TaskItem; contract?: string; attempt: TaskAttempt }) {
  const { t } = useI18n();
  if (attempt.phase === 'EXPAND') {
    return <AttemptChildren task={task} contract={contract} attempt={attempt} />;
  }
  if (attempt.result === undefined || attempt.result === null) {
    return <Typography.Text type="secondary">{t('tasks.attemptNoResult')}</Typography.Text>;
  }
  return <JsonBlock value={attempt.result} />;
}

/**
 * Дети одной попытки раскрытия (docs/adr/0047-children-belong-to-attempt.md): у группы и задачи
 * с подзадачами — подзадачи; у задачи с группами — группы и подзадачи этих групп
 * (docs/adr/0157-attempt-children-through-batches.md). На вкладке — число детей.
 */
function AttemptChildren({ task, contract, attempt }: { task: TaskItem; contract?: string; attempt: TaskAttempt }) {
  const { t } = useI18n();
  const scope = {
    executionId: task.execution.id,
    executionCreatedAt: task.execution.createdAt,
    parentId: task.id,
    parentAttemptId: attempt.id,
  };
  const unitsOnly = task.level === 'BATCH' || contract === 'UNIT';
  const batches = useTaskPages(['attempt', 'BATCH', attempt.id], () => ({ ...scope, level: 'BATCH' }), !unitsOnly);
  const units = useTaskPages(['attempt', 'UNIT', attempt.id], () => ({ ...scope, level: 'UNIT' }));
  const batchCount = useChildCount('BATCH', scope, !unitsOnly);
  const unitCount = useChildCount('UNIT', scope, true);
  const tabs = [
    ...(unitsOnly
      ? []
      : [
          {
            key: 'batches',
            label: <CountLabel label={t('tasks.batches')} count={batchCount} />,
            children: <TaskTable pages={batches} showWindow={false} level="BATCH" taskType={task.taskType} />,
          },
        ]),
    {
      key: 'units',
      label: <CountLabel label={t('tasks.units')} count={unitCount} />,
      children: <TaskTable pages={units} showWindow={false} level="UNIT" taskType={task.taskType} />,
    },
  ];
  return <Tabs size="small" items={tabs} />;
}

/** Число детей уровня по тем же условиям, что у списка на вкладке. */
function useChildCount(level: Level, scope: Record<string, string>, enabled: boolean): number | undefined {
  const count = useQuery({
    queryKey: ['tasks', 'count', level, scope.parentAttemptId],
    queryFn: () => getJson<{ count: number }>('/tasks/count', { ...scope, level }),
    enabled,
  });
  return count.data?.count;
}

/**
 * Результат последней попытки (docs/adr/0058-task-result-storage.md): у плоской задачи и подзадачи —
 * обработки, у задачи и группы с детьми — сборки. Нет результата — так и сказано: процессор вернул
 * null, попытка упала или результат уже удалён очисткой.
 */
function LastResult({ attempts, loading, error }: { attempts?: TaskAttempt[]; loading: boolean; error: unknown }) {
  const { t } = useI18n();
  const last = attempts?.[attempts.length - 1];
  return (
    <Card
      size="small"
      title={t('tasks.lastResult')}
      extra={
        last && (
          <Space size={8}>
            <Typography.Text type="secondary">
              <PhaseLabel phase={last.phase} />
            </Typography.Text>
            <StatusTag status={last.outcome.status ?? 'RUNNING'} description={last.outcome.statusDescription} />
          </Space>
        )
      }
    >
      <Loadable loading={loading} error={error}>
        {() =>
          last === undefined ? (
            <Typography.Text type="secondary">{t('tasks.noAttempts')}</Typography.Text>
          ) : last.result === undefined || last.result === null ? (
            <Typography.Text type="secondary">{t('tasks.noResult')}</Typography.Text>
          ) : (
            <JsonBlock value={last.result} />
          )
        }
      </Loadable>
    </Card>
  );
}
