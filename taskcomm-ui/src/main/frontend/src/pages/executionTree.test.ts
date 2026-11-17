import { describe, expect, it } from 'vitest';
import type { AttemptEntry, CommunicationItem, ExecutionOutline, Origin, TaskItem } from '../api';
import { buildTree, CHILD_LIMIT, restartBox, restartChoices, restartTargets, toggleRestart, total } from './executionTree';

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

  const attempt = (
    id: string,
    kind: AttemptEntry['kind'],
    targetId: string,
    phase: string,
    startedAt: string,
    status?: string,
  ): AttemptEntry => ({ id, kind, targetId, phase, outcome: { status }, times: { startedAt } });

  it('под сущностью — фазы, под фазой — попытки, если их больше одной, под попыткой — созданное ею по времени', () => {
    const spawned = (id: string) => ({ type: 'COMMUNICATION' as const, id: 'c1', attemptId: id, relation: 'SPAWNED' });
    const tree = buildTree({
      ...outline,
      tasks: [
        outline.tasks[0],
        task('t5', '2026-11-12T00:00:07Z', spawned('a2')),
        task('t4', '2026-11-12T00:00:06Z', spawned('a2')),
      ],
      attempts: [
        attempt('a3', 'COMMUNICATION', 'c1', 'HANDLE_RESPONSE', '2026-11-12T00:00:08Z', 'SUCCEEDED'),
        attempt('a2', 'COMMUNICATION', 'c1', 'PROCESS', '2026-11-12T00:00:05Z', 'SUCCEEDED'),
        attempt('a1', 'COMMUNICATION', 'c1', 'PROCESS', '2026-11-12T00:00:04Z', 'FAILED'),
      ],
    });
    const comm = tree[0].children.find((node) => node.key === 'COMMUNICATION:c1')!;

    // У обработки узла фазы нет: две её попытки — прямо под коммуникацией.
    expect(comm.children.map((node) => node.key)).toEqual(['ATTEMPT:a1', 'ATTEMPT:a2', 'PHASE-HANDLE_RESPONSE:COMMUNICATION:c1']);
    expect(comm.children.map((node) => node.attempt?.number)).toEqual([1, 2, undefined]);
    expect(comm.children[1].children.map((node) => node.key)).toEqual(['TASK:t4', 'TASK:t5']);
    expect(comm.processAttempt).toBeUndefined();
    // Одна попытка в фазе узлом не становится.
    const response = comm.children[2];
    expect(response.phase).toMatchObject({ attempts: 1, last: { id: 'a3' } });
    expect(response.children).toEqual([]);
  });

  it('единственная попытка обработки сливается со строкой сущности, созданное ею — прямо под сущностью', () => {
    const spawned = { type: 'COMMUNICATION' as const, id: 'c1', attemptId: 'a1', relation: 'SPAWNED' };
    const tree = buildTree({
      ...outline,
      tasks: [outline.tasks[0], task('t4', '2026-11-12T00:00:06Z', spawned)],
      attempts: [attempt('a1', 'COMMUNICATION', 'c1', 'PROCESS', '2026-11-12T00:00:04Z', 'SUCCEEDED')],
    });
    const comm = tree[0].children.find((node) => node.key === 'COMMUNICATION:c1')!;

    expect(comm.processAttempt).toMatchObject({ id: 'a1' });
    expect(comm.children.map((node) => node.key)).toEqual(['TASK:t4']);
  });

  it('сборка без попыток, пока задача ждёт детей, — узел фазы; у завершённой — нет', () => {
    const waiting = { ...task('t1', '2026-11-12T00:00:01Z'), state: { ...task('t1', '2026-11-12T00:00:01Z').state, status: 'AWAITING_CHILDREN', phase: 'COLLECT' } };
    const base = { ...outline, communications: [], unitCounts: [], attempts: [attempt('x1', 'TASK', 't1', 'EXPAND', '2026-11-12T00:00:01Z', 'SUCCEEDED')] };

    const [root] = buildTree({ ...base, tasks: [waiting] });
    const phases = root.children.filter((node) => node.kind === 'PHASE');
    expect(phases.map((node) => node.key)).toEqual(['PHASE-EXPAND:TASK:t1', 'PHASE-COLLECT:TASK:t1']);
    expect(phases[1].phase).toMatchObject({ attempts: 0, waiting: { status: 'AWAITING_CHILDREN' } });
    // Группы без попытки-создателя — под самой задачей.
    expect(root.children.map((node) => node.key)).toContain('BATCH:b1');

    const done = { ...waiting, state: { ...waiting.state, status: 'SUCCEEDED' } };
    expect(buildTree({ ...base, tasks: [done] })[0].children.filter((node) => node.kind === 'PHASE')).toHaveLength(1);
  });

  it('единственная попытка раскрытия слита с фазой: группы и кнопка «Все подзадачи» — прямо в фазе', () => {
    const batch = (id: string, createdAt: string): TaskItem => ({
      ...task(id, createdAt, undefined, 't1'),
      parents: { taskId: 't1', attemptId: 'x1' },
    });
    const [root] = buildTree({
      ...outline,
      communications: [],
      batches: [batch('b2', '2026-11-12T00:00:03Z'), batch('b1', '2026-11-12T00:00:02Z')],
      attempts: [attempt('x1', 'TASK', 't1', 'EXPAND', '2026-11-12T00:00:01Z', 'SUCCEEDED')],
    });
    const expand = root.children.find((node) => node.kind === 'PHASE')!;

    expect(expand.children.map((node) => node.key)).toEqual(['BATCH:b1', 'BATCH:b2', 'MORE-UNIT:t1']);
  });

  it('старый сервер без попыток — фаз в дереве нет', () => {
    const waiting = { ...task('t1', '2026-11-12T00:00:01Z'), state: { ...task('t1', '2026-11-12T00:00:01Z').state, phase: 'COLLECT' } };

    expect(buildTree({ ...outline, tasks: [waiting] })[0].children.some((node) => node.kind === 'PHASE')).toBe(false);
  });

  it('раскладывает счётчики подзадач по группе и по задаче без групп', () => {
    const [root] = buildTree(outline);
    const batch = root.children[0];

    expect(batch.units).toEqual({ SUCCEEDED: 7, FAILED: 3 });
    expect(total(batch.units)).toBe(10);
    expect(root.units).toEqual({ PENDING: 2 });
  });

  describe('выбор для перезапуска', () => {
    const status = (item: TaskItem, value: string): TaskItem => ({ ...item, state: { ...item.state, status: value } });
    const unit: TaskItem = { ...status(task('u1', '2026-11-12T00:00:09Z'), 'FAILED'), level: 'UNIT', parents: { taskId: 't1', batchId: 'b1' } };
    const tree = () =>
      buildTree({
        ...outline,
        tasks: [outline.tasks[0]],
        batches: [status(outline.batches[1], 'SUCCEEDED')],
        units: [unit],
        unitCounts: [],
      });

    it('сущности — с предками в дереве; доступность — по правилам уровня', () => {
      const choices = restartChoices(tree());

      expect([...choices.keys()]).toEqual(['TASK:t1', 'BATCH:b1', 'UNIT:u1', 'COMMUNICATION:c1']);
      expect(choices.get('UNIT:u1')!.ancestors).toEqual(['BATCH:b1', 'TASK:t1']);
      expect(restartBox('TASK:t1', new Set(), choices)).toEqual({ disabled: true, hint: 'notRestartable' });
      expect(restartBox('COMMUNICATION:c1', new Set(), choices)).toEqual({ disabled: true, hint: 'onlyWithParent' });
      expect(restartBox('UNIT:u1', new Set(), choices)).toEqual({ disabled: false });
    });

    it('выбранный родитель снимает выбор с потомков и закрывает их флажки', () => {
      const choices = restartChoices(tree());

      const unitOnly = toggleRestart(new Set(), choices, 'UNIT:u1', true);
      const withBatch = toggleRestart(unitOnly, choices, 'BATCH:b1', true);

      expect([...withBatch]).toEqual(['BATCH:b1']);
      expect(restartBox('UNIT:u1', withBatch, choices)).toEqual({ disabled: true, hint: 'withParent' });
      expect(restartTargets(withBatch, choices)).toEqual([{ kind: 'BATCH', id: 'b1', executionCreatedAt: EXECUTION.createdAt }]);
      // Подзадача, выбранная до родителя, на перезапуск не уходит, даже если осталась в выборе.
      expect(restartTargets(new Set(['BATCH:b1', 'UNIT:u1']), choices).map((target) => target.id)).toEqual(['b1']);
    });
  });
});
