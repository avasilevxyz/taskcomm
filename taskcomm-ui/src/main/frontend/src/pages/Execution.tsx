import { useQuery } from '@tanstack/react-query';
import { Button, Flex, Space, Tabs, Tag, Tooltip, Tree, Typography } from 'antd';
import type { DataNode } from 'antd/es/tree';
import { useState, useMemo } from 'react';
import { Link, useParams, useSearchParams } from 'react-router';
import { getJson, type AttemptEntry, type CorrelationKey, type DedupKeyEntry, type ExecutionOutline } from '../api';
import {
  CountLabel,
  communicationPath,
  IdLink,
  Loadable,
  OrphanTag,
  SyncTag,
  ProcessorTitle,
  shortId,
  StatusTag,
  by,
  taskPath,
  useTime,
  PhaseLabel,
  Reason,
} from '../common';
import { CreateInExecutionButton } from '../createPanel';
import { attemptLimit, responseTimeout } from '../duration';
import { Dotted, Elapsed, WorkTime } from '../elapsed';
import { useMeta } from '../meta';
import { ColumnsSlotProvider, DataTable, useColumnsSlot } from '../dataTable';
import { useI18n } from '../i18n';
import { rowOpens, useEntityPanel } from '../entityPanel';
import { useOpenPanel } from '../panels';
import { RestartBox, RestartSelected } from '../restartMany';
import {
  buildTree,
  restartBox,
  restartChoices,
  restartTargets,
  toggleRestart,
  attemptTimes,
  treeTimes,
  type MoreNode,
  type NodeTimes,
  type TreeNode,
} from './executionTree';
import { TaskTable, useTaskPages } from './TaskTable';

export function Execution() {
  const { id = '' } = useParams();
  const [search] = useSearchParams();
  const createdAt = search.get('createdAt') ?? undefined;
  const outline = useQuery({
    queryKey: ['executions', 'outline', id, createdAt],
    queryFn: () => getJson<ExecutionOutline>(`/executions/${id}/outline`, { createdAt }),
  });
  return (
    <Loadable loading={outline.isLoading} error={outline.error}>
      {() => (outline.data ? <ExecutionView outline={outline.data} /> : null)}
    </Loadable>
  );
}

function ExecutionView({ outline }: { outline: ExecutionOutline }) {
  const { t } = useI18n();
  const time = useTime();
  const roots = useMemo(() => buildTree(outline), [outline]);
  // Начало и окончание узлов: окончание фазы — окончание всех её детей (docs/adr/0171-durations-and-limits.md).
  const times = useMemo(() => treeTimes(roots), [roots]);
  // Выбор для перезапуска нескольких сразу (docs/adr/0169-restart-many.md): ключи узлов сущностей.
  const choices = useMemo(() => restartChoices(roots), [roots]);
  const [selected, setSelected] = useState<ReadonlySet<string>>(new Set());
  // Раскрыто всё, кроме свёрнутого руками: узлы, появившиеся при опросе, — тоже раскрыты.
  const [collapsed, setCollapsed] = useState<ReadonlySet<string>>(new Set());
  const expanded = useMemo(() => {
    const keys: string[] = [];
    const walk = (node: TreeNode) => {
      if (node.children.length > 0 && !collapsed.has(node.key)) {
        keys.push(node.key);
      }
      node.children.forEach(walk);
    };
    roots.forEach(walk);
    return keys;
  }, [roots, collapsed]);
  const openPanel = useOpenPanel();
  const [tab, setTab] = useState('tree');
  const [slot, slotNode] = useColumnsSlot();
  // Все группы или подзадачи родителя — таблицей в боковой панели стопки (docs/adr/0161-side-panel-stack.md).
  const showAll = (more: MoreNode) =>
    openPanel({
      kind: 'entity',
      label: `${t(more.level === 'UNIT' ? 'tasks.units' : 'tasks.batches')} · ${shortId(more.parentId)}`,
      render: () => (
        <ChildrenOf
          level={more.level}
          parentId={more.parentId}
          taskType={more.taskType}
          executionId={outline.execution.id}
          executionCreatedAt={createdAt}
        />
      ),
    });
  // Щелчок по узлу — карточка в боковой панели, как в таблицах; по фазе и попытке — карточка их сущности.
  const openEntity = useEntityPanel();
  const openNode = (node: TreeNode) => {
    const kind = node.attempt?.kind ?? node.phase?.kind ?? node.kind;
    const id = node.attempt?.targetId ?? node.phase?.targetId ?? node.task?.id ?? node.communication?.id;
    if (!id || kind === 'MORE' || kind === 'ATTEMPT' || kind === 'PHASE') {
      return;
    }
    openEntity(
      kind === 'COMMUNICATION'
        ? { kind: 'communication', id, createdAt: outline.execution.createdAt }
        : { kind: 'task', level: kind, id, createdAt: outline.execution.createdAt },
    );
  };
  const box = (node: TreeNode) => {
    if (!choices.has(node.key)) {
      return null;
    }
    const state = restartBox(node.key, selected, choices);
    return (
      <RestartBox
        checked={selected.has(node.key)}
        disabled={state.disabled}
        hint={state.hint && t(`restart.${state.hint}`)}
        onChange={(checked) => setSelected(toggleRestart(selected, choices, node.key, checked))}
      />
    );
  };
  const toData = (node: TreeNode): DataNode => ({
    key: node.key,
    title: node.more ? (
      <NodeTitle node={node} times={times.get(node.key)} onMore={showAll} />
    ) : (
      <Flex gap={8} align="center">
        {box(node)}
        <div {...rowOpens(() => openNode(node))} style={{ flex: 1, cursor: 'pointer' }}>
          <NodeTitle node={node} times={times.get(node.key)} onMore={showAll} />
        </div>
      </Flex>
    ),
    children: node.children.map(toData),
  });
  const timeline = [
    ...outline.tasks.map((item) => ({ key: `TASK:${item.id}`, kind: 'TASK', item, createdAt: item.state.createdAt })),
    ...outline.batches.map((item) => ({ key: `BATCH:${item.id}`, kind: 'BATCH', item, createdAt: item.state.createdAt })),
    // Подзадачи — те, что в выдаче: не больше 50 у родителя, остальные — по кнопке «Все подзадачи» в дереве.
    ...(outline.units ?? []).map((item) => ({ key: `UNIT:${item.id}`, kind: 'UNIT', item, createdAt: item.state.createdAt })),
  ]
    .map((row) => ({
      key: row.key,
      kind: row.kind as 'TASK' | 'BATCH' | 'UNIT' | 'COMMUNICATION',
      id: row.item.id,
      name: row.item.taskType,
      label: <ProcessorTitle kind="task" id={row.item.taskType} />,
      status: row.item.state.status,
      description: row.item.state.statusDescription,
      createdAt: row.createdAt,
    }))
    .concat(
      outline.communications.map((item) => ({
        key: `COMMUNICATION:${item.id}`,
        kind: 'COMMUNICATION' as const,
        id: item.id,
        name: `${t(`direction.${item.direction}`)} · ${item.externalSystem}`,
        label: (
          <Space size={4}>
            <span>
              {t(`direction.${item.direction}`)} · <ProcessorTitle kind="system" id={item.externalSystem} />
            </span>
            <SyncTag runMode={item.runMode} />
          </Space>
        ),
        status: item.state.status,
        description: item.state.statusDescription,
        createdAt: item.state.createdAt,
      })),
    )
    .sort((a, b) => a.createdAt.localeCompare(b.createdAt));
  const createdAt = outline.execution.createdAt;

  return (
    <Flex vertical gap={16}>
      <Space align="baseline" wrap>
        <Typography.Title level={3} style={{ margin: 0 }}>
          {t('execution.title', { id: shortId(outline.execution.id) })}
        </Typography.Title>
        <Typography.Text copyable type="secondary">
          {outline.execution.id}
        </Typography.Text>
        <Typography.Text type="secondary">{time(createdAt)}</Typography.Text>
        <CreateInExecutionButton executionId={outline.execution.id} executionCreatedAt={createdAt} />
      </Space>
      <Tabs
        activeKey={tab}
        onChange={setTab}
        // Справа от вкладок: у ленты — кнопка «Столбцы», у дерева — перезапуск выбранных.
        tabBarExtraContent={
          tab === 'timeline' ? (
            slot
          ) : tab === 'tree' ? (
            <RestartSelected targets={restartTargets(selected, choices)} onDone={() => setSelected(new Set())} />
          ) : null
        }
        items={[
          {
            key: 'tree',
            label: t('execution.tree'),
            children:
              roots.length === 0 ? (
                <Typography.Text type="secondary">{t('common.empty')}</Typography.Text>
              ) : (
                <Tree
                  blockNode
                  selectable={false}
                  treeData={roots.map(toData)}
                  expandedKeys={expanded}
                  onExpand={(_keys, info) => {
                    const next = new Set(collapsed);
                    if (info.expanded) {
                      next.delete(String(info.node.key));
                    } else {
                      next.add(String(info.node.key));
                    }
                    setCollapsed(next);
                  }}
                />
              ),
          },
          {
            key: 'timeline',
            label: <CountLabel label={t('execution.timeline')} count={timeline.length} />,
            children: (
              <ColumnsSlotProvider node={slotNode}>
              <DataTable
                settings="execution"
                request={{ path: `/executions/${outline.execution.id}/outline`, params: { createdAt } }}
                size="small"
                scroll={{ x: 'max-content' }}
                pagination={false}
                dataSource={timeline}
                // Щелчок по строке — карточка в боковой панели, как в дереве и в списках.
                onRow={(row) =>
                  rowOpens(() =>
                    openEntity(
                      row.kind === 'COMMUNICATION'
                        ? { kind: 'communication', id: row.id, createdAt }
                        : { kind: 'task', level: row.kind, id: row.id, createdAt },
                    ),
                  )
                }
                columns={[
                  { title: t('common.created'), sorter: by((row: { createdAt: string; kind: string; id: string; name: string; status: string }) => row.createdAt), dataIndex: 'createdAt', render: (value: string) => time(value) },
                  { title: t('common.type'), sorter: by((row: { createdAt: string; kind: string; id: string; name: string; status: string }) => row.kind), dataIndex: 'kind', render: (kind: 'TASK') => t(`level.${kind}`) },
                  {
                    key: 'common.id',
                    title: t('common.id'),
                    sorter: by((row: { createdAt: string; kind: string; id: string; name: string; status: string }) => row.id),
                    render: (_, row) => (
                      <IdLink
                        to={
                          row.kind === 'COMMUNICATION'
                            ? communicationPath(row.id, createdAt)
                            : taskPath(row.kind, row.id, createdAt)
                        }
                        id={row.id}
                      />
                    ),
                  },
                  { title: t('common.name'), sorter: by((row: { createdAt: string; kind: string; id: string; name: string; status: string }) => row.name), dataIndex: 'label' },
                  {
                    key: 'common.status',
                    title: t('common.status'),
                    sorter: by((row: { createdAt: string; kind: string; id: string; name: string; status: string }) => row.status),
                    render: (_, row) => <StatusTag status={row.status} description={row.description} />,
                  },
                ]}
              />
              </ColumnsSlotProvider>
            ),
          },
          {
            key: 'correlation',
            label: <CountLabel label={t('execution.correlation')} count={outline.correlationKeys.length} />,
            children: (
              <DataTable<CorrelationKey>
                size="small"
                scroll={{ x: 'max-content' }}
                pagination={false}
                rowKey={(row) => `${row.externalSystem}/${row.correlationId}`}
                dataSource={outline.correlationKeys}
                // Щелчок по строке — карточка коммуникации ключа.
                onRow={(row) =>
                  rowOpens(() => openEntity({ kind: 'communication', id: row.communicationId, createdAt }))
                }
                columns={[
                  { title: t('communications.system'), sorter: by((row: CorrelationKey) => row.externalSystem), dataIndex: 'externalSystem', render: (system: string) => <ProcessorTitle kind="system" id={system} /> },
                  { title: t('execution.correlationId'), sorter: by((row: CorrelationKey) => row.correlationId), dataIndex: 'correlationId' },
                  {
                    title: t('level.COMMUNICATION'),
                    dataIndex: 'communicationId',
                    render: (id: string) => <IdLink to={communicationPath(id, createdAt)} id={id} />,
                  },
                  { title: t('common.created'), sorter: by((row: CorrelationKey) => row.createdAt), dataIndex: 'createdAt', render: (value: string) => time(value) },
                ]}
              />
            ),
          },
          // Ключи дедупликации дела (docs/adr/0172-execution-dedup-keys.md); старый сервер их не отдаёт — вкладки нет.
          ...(outline.dedupKeys
            ? [
                {
                  key: 'dedup',
                  label: <CountLabel label={t('execution.dedup')} count={outline.dedupKeys.length} />,
                  children: (
                    <DataTable<DedupKeyEntry>
                      size="small"
                      scroll={{ x: 'max-content' }}
                      pagination={false}
                      rowKey={(row) => `${row.communicationId}/${row.subject ?? ''}/${row.key}`}
                      dataSource={outline.dedupKeys}
                      onRow={(row) =>
                        rowOpens(() => openEntity({ kind: 'communication', id: row.communicationId, createdAt }))
                      }
                      columns={[
                        { title: t('communications.direction'), sorter: by((row: DedupKeyEntry) => row.direction), dataIndex: 'direction', render: (direction: DedupKeyEntry['direction']) => t(`direction.${direction}`) },
                        { title: t('communications.system'), sorter: by((row: DedupKeyEntry) => row.externalSystem), dataIndex: 'externalSystem', render: (system: string) => <ProcessorTitle kind="system" id={system} /> },
                        {
                          title: (
                            <Tooltip title={t('execution.dedupSubjectHint')}>
                              <span style={{ cursor: 'help', textDecoration: 'underline dotted' }}>{t('execution.dedupSubject')}</span>
                            </Tooltip>
                          ),
                          sorter: by((row: DedupKeyEntry) => row.subject), dataIndex: 'subject',
                          render: (subject?: string | null) => subject ?? '—',
                        },
                        {
                          title: t('execution.correlationId'),
                          sorter: by((row: DedupKeyEntry) => row.key),
                          // Ключ — ссылка на поиск в его пространстве, как в карточке коммуникации.
                          render: (_, row) => <Link to={dedupSearch(row)}>{row.key}</Link>,
                        },
                        {
                          title: t('level.COMMUNICATION'),
                          dataIndex: 'communicationId',
                          render: (id: string) => <IdLink to={communicationPath(id, createdAt)} id={id} />,
                        },
                        { title: t('common.created'), sorter: by((row: DedupKeyEntry) => row.createdAt), dataIndex: 'createdAt', render: (value: string) => time(value) },
                      ]}
                    />
                  ),
                },
              ]
            : []),
        ]}
      />
    </Flex>
  );
}

/** Поиск коммуникаций по ключу дедупликации — в его пространстве: направление и система. */
function dedupSearch(row: DedupKeyEntry): string {
  return `/communications?${new URLSearchParams({
    direction: row.direction,
    system: row.externalSystem,
    dedupKey: row.key,
    ...(row.subject ? { dedupSubject: row.subject } : {}),
  })}`;
}

/** Созданное узлом — не его фазы и попытки: только тогда у строки есть «всего», отличное от её попытки. */
function hasCreated(node: TreeNode): boolean {
  return node.children.some((child) => !child.phase && !child.attempt);
}

function NodeTitle({ node, times, onMore }: { node: TreeNode; times?: NodeTimes; onMore: (more: MoreNode) => void }) {
  const { t } = useI18n();
  const time = useTime();
  const meta = useMeta().data;
  // Суммарное время родителя: от создания (у фазы — от первой попытки) до окончания последней попытки или
  // последнего ребёнка; рядом — работа, сумма попыток здесь и ниже (docs/adr/0171-durations-and-limits.md).
  const totalParts = (withWork: boolean) =>
    times?.start && times.end !== undefined
      ? [
          <Elapsed key="total" start={times.start} end={times.end} limit="none" total />,
          withWork && <WorkTime key="work" {...times.work} />,
        ]
      : [];
  // Начало и длительность попытки, слитой со строкой; начала нет — нет и длительности.
  const attemptParts = (attempt: AttemptEntry | undefined, processor: string) =>
    attempt?.times.startedAt
      ? [
          <Typography.Text key="started" type="secondary">
            {time(attempt.times.startedAt)}
          </Typography.Text>,
          <Elapsed
            key="duration"
            {...attemptTimes(attempt)}
            limit={attemptLimit(meta, attempt.kind, processor, attempt.phase)}
          />,
        ]
      : [];
  const createdAt = node.task?.execution.createdAt ?? node.communication?.execution.createdAt ?? '';
  if (node.more) {
    const more = node.more;
    return (
      <Button size="small" onClick={() => onMore(more)}>
        {t(more.level === 'UNIT' ? 'execution.allUnits' : 'execution.allBatches', { count: more.count })}
      </Button>
    );
  }
  if (node.phase) {
    const phase = node.phase;
    return (
      <Flex gap={8} wrap align="center">
        <Typography.Text strong>
          <PhaseLabel phase={phase.phase} />
        </Typography.Text>
        {phase.waiting ? (
          <>
            <Tag>{t('execution.phaseNotStarted')}</Tag>
            <StatusTag status={phase.waiting.status} description={phase.waiting.statusDescription} />
            {/* Ждём асинхронного ответа: срок — от конца отправки (docs/adr/0171-durations-and-limits.md). */}
            {phase.waiting.since && (
              <Elapsed start={phase.waiting.since} end={null} limit={responseTimeout(meta, phase.processor)} />
            )}
          </>
        ) : (
          <>
            {phase.attempts > 1 && (
              <Typography.Text type="secondary">{t('execution.phaseAttempts', { count: phase.attempts })}</Typography.Text>
            )}
            {phase.last?.outcome.status ? (
              <StatusTag status={phase.last.outcome.status} description={phase.last.outcome.statusDescription} />
            ) : (
              <Tag>{t('execution.attemptOpen')}</Tag>
            )}
            {/* Одна попытка слита с фазой — её время и причина здесь же. */}
            <Dotted
              parts={[
                ...(phase.attempts === 1 ? attemptParts(phase.last, phase.processor) : []),
                ...(phase.attempts > 1 || hasCreated(node) ? totalParts(true) : []),
              ]}
            />
            {phase.attempts === 1 && phase.last?.outcome.statusDescription && (
              <Reason text={phase.last.outcome.statusDescription} width={360} />
            )}
          </>
        )}
      </Flex>
    );
  }
  if (node.attempt) {
    const attempt = node.attempt;
    return (
      <Flex gap={8} wrap align="center">
        <Typography.Text type="secondary">{t('execution.attempt', { number: attempt.number })}</Typography.Text>
        {attempt.outcome.status ? (
          <StatusTag status={attempt.outcome.status} description={attempt.outcome.statusDescription} />
        ) : (
          <Tag>{t('execution.attemptOpen')}</Tag>
        )}
        <Dotted parts={attemptParts(attempt, attempt.processor)} />
        {attempt.outcome.statusDescription && <Reason text={attempt.outcome.statusDescription} width={360} />}
      </Flex>
    );
  }
  return (
    <Flex gap={8} wrap align="center">
      <Typography.Text strong>{t(`level.${node.kind as 'TASK' | 'BATCH' | 'UNIT' | 'COMMUNICATION'}`)}</Typography.Text>
      {node.task && (
        <>
          <IdLink to={taskPath(node.kind, node.task.id, createdAt)} id={node.task.id} />
          <ProcessorTitle kind="task" id={node.task.taskType} />
          <StatusTag status={node.task.state.status} description={node.task.state.statusDescription} />
          <OrphanTag orphan={node.task.state.orphan} />
        </>
      )}
      {node.communication && (
        <>
          <IdLink to={communicationPath(node.communication.id, createdAt)} id={node.communication.id} />
          <span>
            {t(`direction.${node.communication.direction}`)} · <ProcessorTitle kind="system" id={node.communication.externalSystem} />
          </span>
          <StatusTag status={node.communication.state.status} description={node.communication.state.statusDescription} />
          <OrphanTag orphan={node.communication.state.orphan} />
          <SyncTag runMode={node.communication.runMode} />
        </>
      )}
      {/* Единственная попытка обработки слита со строкой сущности — её время здесь. «Всего» у сущности —
          всегда: в нём её ожидание в очереди; работа — когда под ней есть ещё попытки. */}
      <Dotted
        parts={[
          ...attemptParts(node.processAttempt, node.task?.taskType ?? node.communication?.externalSystem ?? ''),
          ...totalParts(!node.processAttempt || node.children.length > 0),
        ]}
      />
      {node.outsideOrigin && (
        <Typography.Text type="secondary">
          {t('execution.createdBy', { type: t(`level.${node.outsideOrigin.type}`), id: shortId(node.outsideOrigin.id) })}
        </Typography.Text>
      )}
    </Flex>
  );
}

/** Все группы или подзадачи родителя — списком, страницами. */
function ChildrenOf({ level, parentId, taskType, executionId, executionCreatedAt }: {
  level: 'BATCH' | 'UNIT';
  parentId: string;
  taskType: string;
  executionId: string;
  executionCreatedAt: string;
}) {
  const pages = useTaskPages(['children-of', level, parentId], () => ({
    level,
    parentId,
    executionId,
    executionCreatedAt,
  }));
  return <TaskTable pages={pages} showWindow={false} level={level} taskType={taskType} />;
}
