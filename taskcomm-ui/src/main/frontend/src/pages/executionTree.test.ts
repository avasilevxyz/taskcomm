import { describe, expect, it } from 'vitest';
import type { AttemptEntry, CommunicationItem, ExecutionOutline, Origin, TaskItem } from '../api';
import { buildTree, CHILD_LIMIT, total } from './executionTree';

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
    // Подзадач в выдаче нет, а по счётчикам они есть — у родителя кнопка «Все подзадачи».
    expect(roots[0].children.map((node) => node.key)).toEqual(['BATCH:b1', 'BATCH:b2', 'COMMUNICATION:c1', 'MORE-UNIT:t1']);
    expect(roots[0].children[2].children.map((node) => node.key)).toEqual(['TASK:t2']);
  });

  it('создатель вне выдачи — корень с пометкой, кем создан', () => {
    const spawned = buildTree(outline)[1];

    expect(spawned.outsideOrigin).toEqual({ type: 'UNIT', id: 'u9', relation: 'SPAWNED' });
    expect(buildTree(outline)[0].outsideOrigin).toBeUndefined();
  });

  it('подзадачи — под группой; сверх выданных — кнопка «Все подзадачи» с полным числом', () => {
    const unit = (id: string, batchId?: string): TaskItem => ({
      ...task(id, '2026-11-12T00:00:09Z'),
      level: 'UNIT',
      parents: { taskId: 't1', batchId },
    });
    const [root] = buildTree({ ...outline, units: [unit('u1', 'b1'), unit('u2', 'b1'), unit('u3')] });
    const batch = root.children.find((node) => node.key === 'BATCH:b1')!;

    expect(batch.children.map((node) => node.key)).toEqual(['UNIT:u1', 'UNIT:u2', 'MORE-UNIT:b1']);
    expect(batch.children[2].more).toMatchObject({ level: 'UNIT', parentId: 'b1', count: 10 });
    // У задачи две подзадачи без групп, выдана одна — тоже кнопка.
    expect(root.children.map((node) => node.key)).toContain('MORE-UNIT:t1');
  });

  it('групп больше предела — первые в дереве, остальные по кнопке «Все группы»', () => {
    const batches = Array.from({ length: CHILD_LIMIT + 2 }, (_, index) =>
      task(`b${String(index).padStart(3, '0')}`, `2026-11-12T00:01:${String(index % 60).padStart(2, '0')}Z`, undefined, 't1'),
    );
    const [root] = buildTree({ ...outline, batches, unitCounts: [], communications: [] });

    expect(root.children.filter((node) => node.kind === 'BATCH')).toHaveLength(CHILD_LIMIT);
    expect(root.children.at(-1)?.more).toMatchObject({ level: 'BATCH', count: CHILD_LIMIT + 2 });
  });

  it('две попытки в фазе — узлами, созданное — под своей попыткой; одна попытка — без узла', () => {
    const attempt = (id: string, targetId: string, phase: string, startedAt: string, status?: string): AttemptEntry => ({
      id,
      kind: 'COMMUNICATION',
      targetId,
      phase,
      outcome: { status },
      times: { startedAt },
    });
    const spawned = { type: 'COMMUNICATION' as const, id: 'c1', attemptId: 'a2', relation: 'SPAWNED' };
    const tree = buildTree({
      ...outline,
      tasks: [outline.tasks[0], task('t2', '2026-11-12T00:00:05Z', spawned)],
      attempts: [
        attempt('a1', 'c1', 'PROCESS', '2026-11-12T00:00:04Z', 'FAILED'),
        attempt('a2', 'c1', 'PROCESS', '2026-11-12T00:00:05Z', 'SUCCEEDED'),
        attempt('a3', 'c1', 'RESPONSE', '2026-11-12T00:00:06Z', 'SUCCEEDED'),
      ],
    });
    const comm = tree[0].children.find((node) => node.key === 'COMMUNICATION:c1')!;

    expect(comm.children.map((node) => node.key)).toEqual(['ATTEMPT:a1', 'ATTEMPT:a2']);
    expect(comm.children.map((node) => node.attempt?.number)).toEqual([1, 2]);
    expect(comm.children[1].children.map((node) => node.key)).toEqual(['TASK:t2']);
  });

  it('раскладывает счётчики подзадач по группе и по задаче без групп', () => {
    const [root] = buildTree(outline);
    const batch = root.children[0];

    expect(batch.units).toEqual({ SUCCEEDED: 7, FAILED: 3 });
    expect(total(batch.units)).toBe(10);
    expect(root.units).toEqual({ PENDING: 2 });
  });
});
