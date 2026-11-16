import type { AttemptEntry, CommunicationItem, ExecutionOutline, Origin, TaskItem } from '../api';

// Дерево дела (docs/adr/0150-execution-outline-and-meta.md, docs/adr/0166-execution-outline-units-and-attempts.md):
// задача — под своим создателем, группа — под своей задачей, подзадача — под группой или, без групп,
// под задачей, коммуникация — под своим создателем. Если у сущности в одной фазе больше одной попытки,
// попытки этой фазы становятся узлами, и созданное каждой из них висит под ней. Детей одного вида
// у родителя — не больше {@link CHILD_LIMIT}, остальные — по кнопке «Все …» в боковой панели.
// Создатель вне выдачи — узел становится корнем с пометкой, кем он создан.

export type NodeKind = 'TASK' | 'BATCH' | 'UNIT' | 'COMMUNICATION' | 'ATTEMPT' | 'MORE';

/** Сколько групп и подзадач у одного родителя показывает дерево. */
export const CHILD_LIMIT = 50;

/** Попытка узлом — с номером в своей фазе. */
export interface AttemptNode extends AttemptEntry {
  number: number;
}

/** Кнопка «Все группы» или «Все подзадачи» — сколько их у родителя всего. */
export interface MoreNode {
  level: 'BATCH' | 'UNIT';
  parentId: string;
  taskType: string;
  count: number;
}

export interface TreeNode {
  key: string;
  kind: NodeKind;
  task?: TaskItem;
  communication?: CommunicationItem;
  attempt?: AttemptNode;
  more?: MoreNode;
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

function entity(kind: NodeKind, item: TaskItem | CommunicationItem): TreeNode {
  return {
    key: key(kind, item.id),
    kind,
    ...(kind === 'COMMUNICATION' ? { communication: item as CommunicationItem } : { task: item as TaskItem }),
    createdAt: item.state.createdAt,
    units: {},
    children: [],
  };
}

/** Кто создал сущность и в какой попытке. */
interface Creator {
  key?: string;
  attemptId?: string;
  origin?: Origin;
}

function creatorOf(node: TreeNode): Creator {
  if (node.kind === 'BATCH' && node.task) {
    return { key: key('TASK', node.task.parents?.taskId ?? ''), attemptId: node.task.parents?.attemptId };
  }
  if (node.kind === 'UNIT' && node.task) {
    const parents = node.task.parents;
    return {
      key: parents?.batchId ? key('BATCH', parents.batchId) : key('TASK', parents?.taskId ?? ''),
      attemptId: parents?.attemptId,
    };
  }
  const origin = node.task?.origin ?? node.communication?.origin;
  return origin ? { key: key(origin.type, origin.id), attemptId: origin.attemptId, origin } : {};
}

/** Группы сверх предела у задачи — их и их подзадач в дереве нет, есть кнопка. */
function hiddenBatches(batches: TaskItem[]): Set<string> {
  const byTask = new Map<string, TaskItem[]>();
  for (const batch of batches) {
    const taskId = batch.parents?.taskId ?? '';
    byTask.set(taskId, [...(byTask.get(taskId) ?? []), batch]);
  }
  const hidden = new Set<string>();
  for (const list of byTask.values()) {
    [...list]
      .sort((a, b) => a.state.createdAt.localeCompare(b.state.createdAt) || a.id.localeCompare(b.id))
      .slice(CHILD_LIMIT)
      .forEach((batch) => hidden.add(batch.id));
  }
  return hidden;
}

/** Узлы попыток: только в фазах, где у сущности больше одной попытки. */
function attemptNodes(owner: TreeNode, attempts: AttemptEntry[]): TreeNode[] {
  const byPhase = new Map<string, AttemptEntry[]>();
  for (const attempt of attempts) {
    byPhase.set(attempt.phase, [...(byPhase.get(attempt.phase) ?? []), attempt]);
  }
  const nodes: TreeNode[] = [];
  for (const list of byPhase.values()) {
    if (list.length > 1) {
      list.forEach((attempt, index) =>
        nodes.push({
          key: key('ATTEMPT', attempt.id),
          kind: 'ATTEMPT',
          attempt: { ...attempt, number: index + 1 },
          createdAt: attempt.times.startedAt ?? owner.createdAt,
          units: {},
          children: [],
        }),
      );
    }
  }
  return nodes;
}

function moreNode(level: 'BATCH' | 'UNIT', owner: TreeNode, count: number): TreeNode {
  const parentId = owner.task?.id ?? '';
  return {
    key: key(`MORE-${level}`, parentId),
    kind: 'MORE',
    more: { level, parentId, taskType: owner.task?.taskType ?? '', count },
    // Кнопка — после всех детей.
    createdAt: '￿',
    units: {},
    children: [],
  };
}

export function buildTree(outline: ExecutionOutline): TreeNode[] {
  const hidden = hiddenBatches(outline.batches);
  const nodes = new Map<string, TreeNode>();
  outline.tasks.forEach((task) => nodes.set(key('TASK', task.id), entity('TASK', task)));
  outline.batches
    .filter((batch) => !hidden.has(batch.id))
    .forEach((batch) => nodes.set(key('BATCH', batch.id), entity('BATCH', batch)));
  (outline.units ?? [])
    .filter((unit) => !unit.parents?.batchId || !hidden.has(unit.parents.batchId))
    .forEach((unit) => nodes.set(key('UNIT', unit.id), entity('UNIT', unit)));
  outline.communications.forEach((item) => nodes.set(key('COMMUNICATION', item.id), entity('COMMUNICATION', item)));

  for (const count of outline.unitCounts) {
    const owner = nodes.get(count.batchId ? key('BATCH', count.batchId) : key('TASK', count.taskId));
    if (owner) {
      owner.units[count.status] = (owner.units[count.status] ?? 0) + count.count;
    }
  }

  // Попытки — узлами под своей сущностью, когда в фазе их больше одной.
  const attemptsOf = new Map<string, AttemptEntry[]>();
  for (const attempt of outline.attempts ?? []) {
    const owner = key(attempt.kind, attempt.targetId);
    attemptsOf.set(owner, [...(attemptsOf.get(owner) ?? []), attempt]);
  }
  const attemptById = new Map<string, TreeNode>();
  for (const [ownerKey, attempts] of attemptsOf) {
    const owner = nodes.get(ownerKey);
    if (owner) {
      for (const node of attemptNodes(owner, attempts)) {
        owner.children.push(node);
        attemptById.set(node.attempt!.id, node);
      }
    }
  }

  const roots: TreeNode[] = [];
  const shownUnits = new Map<string, number>();
  for (const node of nodes.values()) {
    const creator = creatorOf(node);
    const parent =
      (creator.attemptId ? attemptById.get(creator.attemptId) : undefined) ??
      (creator.key ? nodes.get(creator.key) : undefined);
    if (node.kind === 'UNIT') {
      shownUnits.set(creator.key ?? '', (shownUnits.get(creator.key ?? '') ?? 0) + 1);
    }
    if (parent) {
      parent.children.push(node);
    } else {
      node.outsideOrigin = creator.origin;
      roots.push(node);
    }
  }

  // «Все группы» и «Все подзадачи» — у родителя, у которого в дереве не все.
  const batchesOf = new Map<string, number>();
  outline.batches.forEach((batch) =>
    batchesOf.set(batch.parents?.taskId ?? '', (batchesOf.get(batch.parents?.taskId ?? '') ?? 0) + 1),
  );
  for (const node of nodes.values()) {
    if (node.kind === 'TASK' && node.task && (batchesOf.get(node.task.id) ?? 0) > CHILD_LIMIT) {
      node.children.push(moreNode('BATCH', node, batchesOf.get(node.task.id)!));
    }
    const count = total(node.units);
    if ((node.kind === 'TASK' || node.kind === 'BATCH') && count > (shownUnits.get(node.key) ?? 0)) {
      node.children.push(moreNode('UNIT', node, count));
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
