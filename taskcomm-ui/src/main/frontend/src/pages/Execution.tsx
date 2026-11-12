import { useQuery } from '@tanstack/react-query';
import { Button, Card, Drawer, Flex, Progress, Space, Table, Tabs, Tree, Typography } from 'antd';
import type { DataNode } from 'antd/es/tree';
import { useMemo, useState } from 'react';
import { useParams, useSearchParams } from 'react-router';
import { getJson, type CorrelationKey, type ExecutionOutline } from '../api';
import {
  communicationPath,
  IdLink,
  Loadable,
  OrphanTag,
  shortId,
  StatusTag,
  taskPath,
  useTime,
} from '../common';
import { useI18n } from '../i18n';
import { buildTree, total, type TreeNode } from './executionTree';
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
  const [units, setUnits] = useState<TreeNode | null>(null);
  const toData = (node: TreeNode): DataNode => ({
    key: node.key,
    title: <NodeTitle node={node} onUnits={() => setUnits(node)} />,
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
      </Space>
      <Tabs
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
            label: t('execution.timeline'),
            children: (
              <Table
                size="small"
                pagination={false}
                dataSource={timeline}
                columns={[
                  { title: t('common.created'), dataIndex: 'createdAt', render: (value: string) => time(value) },
                  { title: t('common.type'), dataIndex: 'kind', render: (kind: 'TASK') => t(`level.${kind}`) },
                  {
                    title: t('common.id'),
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
                  { title: '', dataIndex: 'name' },
                  {
                    title: t('common.status'),
                    render: (_, row) => <StatusTag status={row.status} description={row.description} />,
                  },
                ]}
              />
            ),
          },
          {
            key: 'correlation',
            label: `${t('execution.correlation')} (${outline.correlationKeys.length})`,
            children: (
              <Table<CorrelationKey>
                size="small"
                pagination={false}
                rowKey={(row) => `${row.externalSystem}/${row.correlationId}`}
                dataSource={outline.correlationKeys}
                columns={[
                  { title: t('communications.system'), dataIndex: 'externalSystem' },
                  { title: t('execution.correlationId'), dataIndex: 'correlationId' },
                  {
                    title: t('level.COMMUNICATION'),
                    dataIndex: 'communicationId',
                    render: (id: string) => <IdLink to={communicationPath(id, createdAt)} id={id} />,
                  },
                  { title: t('common.created'), dataIndex: 'createdAt', render: (value: string) => time(value) },
                ]}
              />
            ),
          },
        ]}
      />
      <Drawer
        open={units !== null}
        onClose={() => setUnits(null)}
        size="large"
        title={units ? `${t('tasks.units')} · ${shortId(units.task?.id ?? '')}` : ''}
        destroyOnHidden
      >
        {units?.task && (
          <UnitsOf parentId={units.task.id} executionId={outline.execution.id} executionCreatedAt={createdAt} />
        )}
      </Drawer>
    </Flex>
  );
}

function NodeTitle({ node, onUnits }: { node: TreeNode; onUnits: () => void }) {
  const { t } = useI18n();
  const createdAt = node.task?.execution.createdAt ?? node.communication?.execution.createdAt ?? '';
  const count = total(node.units);
  return (
    <Flex gap={8} wrap align="center">
      <Typography.Text strong>{t(`level.${node.kind}`)}</Typography.Text>
      {node.task && (
        <>
          <IdLink to={taskPath(node.kind, node.task.id, createdAt)} id={node.task.id} />
          <span>{node.task.taskType}</span>
          <StatusTag status={node.task.state.status} description={node.task.state.statusDescription} />
          <OrphanTag orphan={node.task.state.orphan} />
        </>
      )}
      {node.communication && (
        <>
          <IdLink to={communicationPath(node.communication.id, createdAt)} id={node.communication.id} />
          <span>
            {t(`direction.${node.communication.direction}`)} · {node.communication.externalSystem}
          </span>
          <StatusTag status={node.communication.state.status} description={node.communication.state.statusDescription} />
          <OrphanTag orphan={node.communication.state.orphan} />
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
            <Button size="small" onClick={onUnits}>
              {t('execution.showUnits')}
            </Button>
          </Space>
        </Card>
      )}
    </Flex>
  );
}

function UnitsOf({ parentId, executionId, executionCreatedAt }: {
  parentId: string;
  executionId: string;
  executionCreatedAt: string;
}) {
  const pages = useTaskPages(['units-of', parentId], () => ({
    level: 'UNIT',
    parentId,
    executionId,
    executionCreatedAt,
  }));
  return <TaskTable pages={pages} showWindow={false} />;
}
