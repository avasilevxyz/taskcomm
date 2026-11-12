import { describe, expect, it } from 'vitest';
import type { CommunicationItem, ExecutionOutline, Origin, TaskItem } from '../api';
import { buildTree, total } from './executionTree';

const EXECUTION = { id: 'e', createdAt: '2026-11-12T00:00:00Z' };

function task(id: string, createdAt: string, origin?: Origin, taskId?: string): TaskItem {
  return {
    id,
    level: taskId ? 'BATCH' : 'TASK',
    taskType: 'orders',
    execution: EXECUTION,
    parents: taskId ? { taskId } : undefined,
    state: { status: 'RUNNING', priority: 100, createdAt, updatedAt: createdAt, orphan: false },
    origin,
    payload: {},
  };
}

function communication(id: string, createdAt: string, origin?: Origin): CommunicationItem {
  return {
    id,
    direction: 'OUTBOUND',
    externalSystem: 'bank',
    execution: EXECUTION,
    state: { status: 'PENDING', phase: 'PROCESS', priority: 100, createdAt, updatedAt: createdAt, orphan: false },
    origin,
  };
}

describe('дерево дела', () => {
  const outline: ExecutionOutline = {
    execution: EXECUTION,
    tasks: [
      task('t1', '2026-11-12T00:00:01Z'),
      task('t2', '2026-11-12T00:00:05Z', { type: 'COMMUNICATION', id: 'c1', relation: 'SPAWNED' }),
      task('t3', '2026-11-12T00:00:06Z', { type: 'UNIT', id: 'u9', relation: 'SPAWNED' }),
    ],
    batches: [task('b2', '2026-11-12T00:00:03Z', undefined, 't1'), task('b1', '2026-11-12T00:00:02Z', undefined, 't1')],
    unitCounts: [
      { taskId: 't1', batchId: 'b1', status: 'SUCCEEDED', count: 7 },
      { taskId: 't1', batchId: 'b1', status: 'FAILED', count: 3 },
      { taskId: 't1', batchId: null, status: 'PENDING', count: 2 },
    ],
    communications: [communication('c1', '2026-11-12T00:00:04Z', { type: 'TASK', id: 't1', relation: 'SPAWNED' })],
    correlationKeys: [],
  };

  it('кладёт группы под задачу, коммуникацию и порождённую задачу — под создателя, по времени', () => {
    const roots = buildTree(outline);

    expect(roots.map((node) => node.key)).toEqual(['TASK:t1', 'TASK:t3']);
    expect(roots[0].children.map((node) => node.key)).toEqual(['BATCH:b1', 'BATCH:b2', 'COMMUNICATION:c1']);
    expect(roots[0].children[2].children.map((node) => node.key)).toEqual(['TASK:t2']);
  });

  it('создатель вне выдачи — корень с пометкой, кем создан', () => {
    const spawned = buildTree(outline)[1];

    expect(spawned.outsideOrigin).toEqual({ type: 'UNIT', id: 'u9', relation: 'SPAWNED' });
    expect(buildTree(outline)[0].outsideOrigin).toBeUndefined();
  });

  it('раскладывает счётчики подзадач по группе и по задаче без групп', () => {
    const [root] = buildTree(outline);
    const batch = root.children[0];

    expect(batch.units).toEqual({ SUCCEEDED: 7, FAILED: 3 });
    expect(total(batch.units)).toBe(10);
    expect(root.units).toEqual({ PENDING: 2 });
  });
});
