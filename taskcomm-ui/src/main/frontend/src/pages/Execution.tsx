import { useQuery } from '@tanstack/react-query';
import { Button, Card, Flex, Progress, Space, Tabs, Tag, Tree, Typography } from 'antd';
import type { DataNode } from 'antd/es/tree';
import { useState, useMemo } from 'react';
import { useParams, useSearchParams } from 'react-router';
import { getJson, type CorrelationKey, type ExecutionOutline } from '../api';
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
import { ColumnsSlotProvider, DataTable, useColumnsSlot } from '../dataTable';
import { useI18n } from '../i18n';
import { rowOpens, useEntityPanel } from '../entityPanel';
import { useOpenPanel } from '../panels';
import { buildTree, total, type MoreNode, type TreeNode } from './executionTree';
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
  // Щелчок по узлу — карточка в боковой панели, как в таблицах; по попытке — карточка её сущности.
  const openEntity = useEntityPanel();
  const openNode = (node: TreeNode) => {
    const kind = node.attempt?.kind ?? node.kind;
    const id = node.attempt?.targetId ?? node.task?.id ?? node.communication?.id;
    if (!id || kind === 'MORE' || kind === 'ATTEMPT') {
      return;
    }
    openEntity(
      kind === 'COMMUNICATION'
        ? { kind: 'communication', id, createdAt: outline.execution.createdAt }
        : { kind: 'task', level: kind, id, createdAt: outline.execution.createdAt },
    );
  };
  const toData = (node: TreeNode): DataNode => ({
    key: node.key,
    title: node.more ? (
      <NodeTitle node={node} onMore={showAll} />
    ) : (
      <div {...rowOpens(() => openNode(node))}>
        <NodeTitle node={node} onMore={showAll} />
      </div>
    ),
    children: node.children.map(toData),
  });
  const timeline = [
    ...outline.tasks.map((item) => ({ key: `TASK:${item.id}`, kind: 'TASK', item, createdAt: item.state.createdAt })),
    ...outline.batches.map((item) => ({ key: `BATCH:${item.id}`, kind: 'BATCH', item, createdAt: item.state.createdAt })),
  ]
    .map((row) => ({
      key: row.key,
      kind: row.kind as 'TASK' | 'BATCH' | 'COMMUNICATION',
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
        // Кнопка «Столбцы» ленты — справа от вкладок, пока открыта лента.
        tabBarExtraContent={tab === 'timeline' ? slot : null}
        items={[
          {
            key: 'tree',
            label: t('execution.tree'),
            children:
              roots.length === 0 ? (
                <Typography.Text type="secondary">{t('common.empty')}</Typography.Text>
              ) : (
                <Tree blockNode defaultExpandAll selectable={false} treeData={roots.map(toData)} />
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
        ]}
      />
    </Flex>
  );
}

function NodeTitle({ node, onMore }: { node: TreeNode; onMore: (more: MoreNode) => void }) {
  const { t } = useI18n();
  const time = useTime();
  const createdAt = node.task?.execution.createdAt ?? node.communication?.execution.createdAt ?? '';
  const count = total(node.units);
  if (node.more) {
    const more = node.more;
    return (
      <Button size="small" onClick={() => onMore(more)}>
        {t(more.level === 'UNIT' ? 'execution.allUnits' : 'execution.allBatches', { count: more.count })}
      </Button>
    );
  }
  if (node.attempt) {
    const attempt = node.attempt;
    return (
      <Flex gap={8} wrap align="center">
        <Typography.Text type="secondary">{t('execution.attempt', { number: attempt.number })}</Typography.Text>
        <PhaseLabel phase={attempt.phase} />
        {attempt.outcome.status ? (
          <StatusTag status={attempt.outcome.status} description={attempt.outcome.statusDescription} />
        ) : (
          <Tag>{t('execution.attemptOpen')}</Tag>
        )}
        {attempt.times.startedAt && <Typography.Text type="secondary">{time(attempt.times.startedAt)}</Typography.Text>}
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
      {node.outsideOrigin && (
        <Typography.Text type="secondary">
          {t('execution.createdBy', { type: t(`level.${node.outsideOrigin.type}`), id: shortId(node.outsideOrigin.id) })}
        </Typography.Text>
      )}
      {count > 0 && (
        <Card size="small" styles={{ body: { padding: '2px 8px' } }}>
          <Space size={8} wrap>
            <Typography.Text>{t('execution.units', { count })}</Typography.Text>
            <Progress
              style={{ width: 120, margin: 0 }}
              size="small"
              showInfo={false}
              percent={Math.round((100 * (count - (node.units.PENDING ?? 0) - (node.units.RUNNING ?? 0) - (node.units.AWAITING_PARENT ?? 0))) / count)}
              success={{ percent: Math.round((100 * (node.units.SUCCEEDED ?? 0)) / count) }}
              status={(node.units.FAILED ?? 0) > 0 ? 'exception' : 'normal'}
            />
            {Object.entries(node.units).map(([status, value]) => (
              <Space key={status} size={2}>
                <StatusTag status={status} />
                {value}
              </Space>
            ))}
          </Space>
        </Card>
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
