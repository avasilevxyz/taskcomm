import type { CommunicationItem, ExecutionOutline, Origin, TaskItem } from '../api';

// Дерево дела из выдачи без подзадач (docs/adr/0150-execution-outline-and-meta.md): задача — под своим
// создателем, группа — под своей задачей, коммуникация — под своим создателем. Создатель вне выдачи
// (подзадача — их в выдаче нет) — узел становится корнем с пометкой, кем он создан.

export type NodeKind = 'TASK' | 'BATCH' | 'COMMUNICATION';

export interface TreeNode {
  key: string;
  kind: NodeKind;
  task?: TaskItem;
  communication?: CommunicationItem;
  createdAt: string;
  /** Создатель, которого нет в выдаче. */
  outsideOrigin?: Origin;
  /** Подзадачи прямо под узлом по статусам: у задачи — без групп, у группы — свои. */
  units: Record<string, number>;
  children: TreeNode[];
}

function key(kind: string, id: string): string {
  return `${kind}:${id}`;
}

export function buildTree(outline: ExecutionOutline): TreeNode[] {
  const nodes = new Map<string, TreeNode>();
  const parents = new Map<string, string | undefined>();
  const origins = new Map<string, Origin | undefined>();

  for (const task of outline.tasks) {
    const node: TreeNode = { key: key('TASK', task.id), kind: 'TASK', task, createdAt: task.state.createdAt, units: {}, children: [] };
    nodes.set(node.key, node);
    parents.set(node.key, task.origin ? key(task.origin.type, task.origin.id) : undefined);
    origins.set(node.key, task.origin);
  }
  for (const batch of outline.batches) {
    const node: TreeNode = { key: key('BATCH', batch.id), kind: 'BATCH', task: batch, createdAt: batch.state.createdAt, units: {}, children: [] };
    nodes.set(node.key, node);
    parents.set(node.key, batch.parents?.taskId ? key('TASK', batch.parents.taskId) : undefined);
  }
  for (const communication of outline.communications) {
    const node: TreeNode = {
      key: key('COMMUNICATION', communication.id),
      kind: 'COMMUNICATION',
      communication,
      createdAt: communication.state.createdAt,
      units: {},
      children: [],
    };
    nodes.set(node.key, node);
    parents.set(node.key, communication.origin ? key(communication.origin.type, communication.origin.id) : undefined);
    origins.set(node.key, communication.origin);
  }
  for (const count of outline.unitCounts) {
    const owner = nodes.get(count.batchId ? key('BATCH', count.batchId) : key('TASK', count.taskId));
    if (owner) {
      owner.units[count.status] = (owner.units[count.status] ?? 0) + count.count;
    }
  }

  const roots: TreeNode[] = [];
  for (const node of nodes.values()) {
    const parentKey = parents.get(node.key);
    const parent = parentKey === undefined ? undefined : nodes.get(parentKey);
    if (parent) {
      parent.children.push(node);
    } else {
      node.outsideOrigin = parentKey === undefined ? undefined : origins.get(node.key);
      roots.push(node);
    }
  }
  const sort = (list: TreeNode[]) => {
    list.sort((a, b) => a.createdAt.localeCompare(b.createdAt) || a.key.localeCompare(b.key));
    list.forEach((node) => sort(node.children));
  };
  sort(roots);
  return roots;
}

export function total(units: Record<string, number>): number {
  return Object.values(units).reduce((sum, count) => sum + count, 0);
}
