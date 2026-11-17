import type { AttemptEntry, CommunicationItem, ExecutionOutline, Level, Origin, TaskItem } from '../api';
import { between, combineEnds, earliest, type End } from '../duration';
import { communicationRestartable, taskRestartable, type RestartKind, type RestartTarget } from '../restartMany';

// Дерево дела (docs/adr/0150-execution-outline-and-meta.md, docs/adr/0166-execution-outline-units-and-attempts.md,
// docs/adr/0168-execution-tree-phases.md): задача — под своим создателем, группа — под своей задачей, подзадача —
// под группой или, без групп, под задачей, коммуникация — под своим создателем. Под сущностью — её фазы, под фазой —
// попытки, если их больше одной, под попыткой — созданное ею, всё в порядке времени. Одна попытка в фазе узлом
// не становится: созданное ею — прямо под фазой. У обработки узла фазы нет: её попытки — прямо под сущностью,
// а одна попытка сливается со строкой сущности. Текущая фаза без попыток — тоже узел: сборка, пока
// задача ждёт подзадач. Детей одного вида у родителя — не больше {@link CHILD_LIMIT}, остальные — по кнопке «Все …»
// в боковой панели, она висит в фазе раскрытия. Создатель вне выдачи — узел становится корнем с пометкой.

export type NodeKind = 'TASK' | 'BATCH' | 'UNIT' | 'COMMUNICATION' | 'PHASE' | 'ATTEMPT' | 'MORE';

/** Сколько групп и подзадач у одного родителя показывает дерево. */
export const CHILD_LIMIT = 50;

/** Попытка узлом — с номером в своей фазе. */
export interface AttemptNode extends AttemptEntry {
  number: number;
  /** Тип задачи или система сущности — по ним ищется предел попытки. */
  processor: string;
}

/** Фаза сущности: её попытки — детьми узла; попыток нет — фаза текущая и ещё не начата. */
export interface PhaseNode {
  phase: string;
  kind: AttemptEntry['kind'];
  targetId: string;
  /** Тип задачи или система сущности — по ним ищется предел попытки. */
  processor: string;
  attempts: number;
  /** Попытки фазы по началу. */
  list: AttemptEntry[];
  /** Последняя попытка фазы. */
  last?: AttemptEntry;
  /**
   * Статус сущности — у текущей фазы без попыток; {@code since} — у асинхронного ответа, который ещё
   * ждём: конец последней отправки, от него идёт срок ожидания.
   */
  waiting?: { status: string; statusDescription?: string; since?: string };
}

/** Кнопка «Все группы» или «Все подзадачи» — сколько их у родителя всего. */
export interface MoreNode {
  level: 'BATCH' | 'UNIT';
  parentId: string;
  taskType: string;
  count: number;
  /** Когда закончились все скрытые: идёт хоть одна — {@code null}. */
  end: End;
}

export interface TreeNode {
  key: string;
  kind: NodeKind;
  task?: TaskItem;
  communication?: CommunicationItem;
  phase?: PhaseNode;
  attempt?: AttemptNode;
  more?: MoreNode;
  createdAt: string;
  /** Единственная попытка обработки, слитая со строкой сущности: её время — в этой строке. */
  processAttempt?: AttemptEntry;
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

// Порядок узлов без времени: фаза без попыток — после начатых, кнопка «Все …» — последней.
const WAITING = '\uFFFE';
const LAST = '\uFFFF';

export const TERMINAL = ['SUCCEEDED', 'FAILED', 'WARNING', 'CANCELLED', 'SKIPPED', 'ABANDONED'];

function byStart(a: AttemptEntry, b: AttemptEntry): number {
  return compare(a.times.startedAt ?? '', b.times.startedAt ?? '') || compare(a.id, b.id);
}

/** Фаза без своего узла: у плоской задачи, подзадачи и коммуникации она — вся работа сущности. */
const PROCESS = 'PROCESS';

/**
 * Фазы сущности, фазы — по первой попытке. Попытки — узлами под фазой, только когда их в фазе больше
 * одной: узел нужен, чтобы отличить одну от другой. Одна попытка сливается с фазой, и созданное ею
 * висит прямо под фазой. У обработки фазы-узла нет: попытки — прямо под сущностью, одна попытка
 * сливается с самой сущностью. Текущая фаза незавершённой сущности без попыток — узлом без детей.
 *
 * @returns узлы под сущностью — фазы и попытки обработки — и узел, под которым висит созданное каждой
 *     попыткой, по её идентификатору
 */
function phaseNodes(owner: TreeNode, attempts: AttemptEntry[]): { phases: TreeNode[]; holders: Map<string, TreeNode> } {
  const target = owner.task ?? owner.communication;
  const kind = owner.kind as AttemptEntry['kind'];
  const processor = owner.task?.taskType ?? owner.communication?.externalSystem ?? '';
  const byPhase = new Map<string, AttemptEntry[]>();
  for (const attempt of [...attempts].sort(byStart)) {
    byPhase.set(attempt.phase, [...(byPhase.get(attempt.phase) ?? []), attempt]);
  }
  const phases: TreeNode[] = [];
  const holders = new Map<string, TreeNode>();
  for (const [phase, list] of byPhase) {
    const attemptNodes = list.map(
      (attempt, index): TreeNode => ({
        key: key('ATTEMPT', attempt.id),
        kind: 'ATTEMPT',
        attempt: { ...attempt, number: index + 1, processor },
        createdAt: attempt.times.startedAt ?? owner.createdAt,
        units: {},
        children: [],
      }),
    );
    const single = attemptNodes.length === 1;
    if (phase === PROCESS) {
      if (single) {
        owner.processAttempt = list[0];
        holders.set(list[0].id, owner);
      } else {
        phases.push(...attemptNodes);
        attemptNodes.forEach((attempt) => holders.set(attempt.attempt!.id, attempt));
      }
      continue;
    }
    const node: TreeNode = {
      key: key(`PHASE-${phase}`, owner.key),
      kind: 'PHASE',
      phase: { phase, kind, targetId: target?.id ?? '', processor, attempts: list.length, list, last: list.at(-1) },
      createdAt: attemptNodes[0].createdAt,
      units: {},
      children: single ? [] : attemptNodes,
    };
    phases.push(node);
    attemptNodes.forEach((attempt) => holders.set(attempt.attempt!.id, single ? node : attempt));
  }
  const current = target?.state.phase;
  if (target && current && current !== PROCESS && !byPhase.has(current) && !TERMINAL.includes(target.state.status)) {
    phases.push({
      key: key(`PHASE-${current}`, owner.key),
      kind: 'PHASE',
      phase: {
        phase: current,
        kind,
        targetId: target.id,
        processor,
        attempts: 0,
        list: [],
        waiting: {
          status: target.state.status,
          statusDescription: target.state.statusDescription,
          since: target.state.status === 'AWAITING_RESPONSE' ? lastFinished(byPhase.get(PROCESS)) : undefined,
        },
      },
      createdAt: WAITING,
      units: {},
      children: [],
    });
  }
  return { phases, holders };
}

/** Конец последней закрытой попытки отправки — от него идёт срок ожидания асинхронного ответа. */
function lastFinished(attempts: AttemptEntry[] | undefined): string | undefined {
  return attempts?.filter((attempt) => attempt.times.finishedAt).at(-1)?.times.finishedAt;
}

function moreNode(level: 'BATCH' | 'UNIT', owner: TreeNode, count: number, end: End): TreeNode {
  const parentId = owner.task?.id ?? '';
  return {
    key: key(`MORE-${level}`, parentId),
    kind: 'MORE',
    more: { level, parentId, taskType: owner.task?.taskType ?? '', count, end },
    createdAt: LAST,
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

  // Под сущностью — фазы, под фазой — попытки. Старый сервер попыток не отдаёт — фаз в дереве нет.
  const attemptsOf = new Map<string, AttemptEntry[]>();
  for (const attempt of outline.attempts ?? []) {
    const owner = key(attempt.kind, attempt.targetId);
    attemptsOf.set(owner, [...(attemptsOf.get(owner) ?? []), attempt]);
  }
  const attemptById = new Map<string, TreeNode>();
  const phasesOf = new Map<string, Map<string, TreeNode>>();
  if (outline.attempts) {
    for (const owner of nodes.values()) {
      const { phases, holders } = phaseNodes(owner, attemptsOf.get(owner.key) ?? []);
      owner.children.push(...phases);
      phasesOf.set(owner.key, new Map(phases.filter((node) => node.phase).map((node) => [node.phase!.phase, node])));
      holders.forEach((node, id) => attemptById.set(id, node));
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
  // Кнопка — в фазе раскрытия, рядом с её попытками; фазы нет — под самой сущностью.
  const expansionOf = (node: TreeNode) => phasesOf.get(node.key)?.get('EXPAND') ?? node;
  for (const node of nodes.values()) {
    if (node.kind === 'TASK' && node.task && (batchesOf.get(node.task.id) ?? 0) > CHILD_LIMIT) {
      const taskId = node.task.id;
      const end = hiddenBatchesEnd(outline, (batch) => batch.parents?.taskId === taskId && hidden.has(batch.id));
      expansionOf(node).children.push(moreNode('BATCH', node, batchesOf.get(taskId)!, end));
    }
    const count = total(node.units);
    if ((node.kind === 'TASK' || node.kind === 'BATCH') && count > (shownUnits.get(node.key) ?? 0)) {
      expansionOf(node).children.push(moreNode('UNIT', node, count, unitsEnd(outline, node)));
    }
  }

  const sort = (list: TreeNode[]) => {
    list.sort((a, b) => compare(a.createdAt, b.createdAt) || compare(a.key, b.key));
    list.forEach((node) => sort(node.children));
  };
  sort(roots);
  return roots;
}

/**
 * Когда закончились подзадачи родителя — по счётчикам, в них есть и не попавшие в дерево
 * (docs/adr/0171-durations-and-limits.md): незавершённая есть — ещё идут, иначе — последнее изменение.
 */
function unitsEnd(outline: ExecutionOutline, owner: TreeNode): End {
  const counts = outline.unitCounts.filter((count) =>
    owner.kind === 'BATCH' ? count.batchId === owner.task?.id : count.taskId === owner.task?.id && !count.batchId,
  );
  return combineEnds(counts.map((count) => (TERMINAL.includes(count.status) ? count.lastUpdatedAt : null)));
}

/** Когда закончились скрытые группы: каждая — со своими подзадачами; закрытая без них — по времени изменения. */
function hiddenBatchesEnd(outline: ExecutionOutline, hiddenOf: (batch: TaskItem) => boolean): End {
  return combineEnds(
    outline.batches.filter(hiddenOf).map((batch) => {
      if (!TERMINAL.includes(batch.state.status)) {
        return null;
      }
      const ends = outline.unitCounts
        .filter((count) => count.batchId === batch.id)
        .map((count) => (TERMINAL.includes(count.status) ? count.lastUpdatedAt : null));
      return combineEnds([batch.state.updatedAt, ...ends]);
    }),
  );
}

/**
 * Работа — время самих попыток, без ожидания: закрытые — суммой, идущие — началами, их время растёт
 * до текущего момента.
 */
export interface Work {
  closedMs: number;
  openStarts: string[];
}

/** Начало и окончание узла дерева и работа попыток в нём и под ним. */
export interface NodeTimes {
  start?: string;
  end: End;
  work: Work;
}

const NO_WORK: Work = { closedMs: 0, openStarts: [] };

function attemptWork(attempt: AttemptEntry): Work {
  const { startedAt, finishedAt } = attempt.times;
  if (!startedAt) {
    return NO_WORK;
  }
  return finishedAt ? { closedMs: between(startedAt, finishedAt), openStarts: [] } : { closedMs: 0, openStarts: [startedAt] };
}

function sumWork(parts: Work[]): Work {
  return {
    closedMs: parts.reduce((sum, part) => sum + part.closedMs, 0),
    openStarts: parts.flatMap((part) => part.openStarts),
  };
}

/** Своё начало и окончание попытки, без созданного ею. */
export function attemptTimes(attempt: AttemptEntry): Omit<NodeTimes, 'work'> {
  return {
    start: attempt.times.startedAt,
    end: attempt.times.startedAt ? (attempt.times.finishedAt ?? null) : undefined,
  };
}

/**
 * Начало, окончание и работа узлов дерева (docs/adr/0171-durations-and-limits.md). Окончание — последняя
 * попытка или последний из созданных, и пока хоть что-то из этого идёт, идёт и узел. Начало у попытки
 * и фазы — их первая попытка, у сущности — её создание: ожидание в очереди входит и в её «всего», и во
 * «всего» узла над ней, и строки складываются. Сущность не в конечном статусе ещё идёт. Работа — сумма
 * попыток узла и всего под ним; подзадач вне выдачи в ней нет.
 */
export function treeTimes(roots: TreeNode[]): Map<string, NodeTimes> {
  const times = new Map<string, NodeTimes>();
  const visit = (node: TreeNode): NodeTimes => {
    const children = node.children.map(visit);
    const childEnds = children.map((child) => child.end);
    const childWork = children.map((child) => child.work);
    let result: NodeTimes;
    if (node.attempt) {
      const own = attemptTimes(node.attempt);
      result = {
        start: own.start,
        end: own.start ? combineEnds([own.end, ...childEnds]) : undefined,
        work: sumWork([attemptWork(node.attempt), ...childWork]),
      };
    } else if (node.phase) {
      const phase = node.phase;
      const own = phase.list.map(attemptTimes);
      const start = earliest(own.map((item) => item.start));
      let end: End = undefined;
      if (phase.waiting) {
        end = null;
      } else if (start) {
        end = combineEnds([...own.map((item) => item.end), ...childEnds]);
      }
      // Попыток больше одной — они сами узлы под фазой и уже посчитаны в детях.
      const ownWork = phase.list.length === 1 ? [attemptWork(phase.list[0])] : [];
      result = { start, end, work: sumWork([...ownWork, ...childWork]) };
    } else if (node.more) {
      result = { end: node.more.end, work: NO_WORK };
    } else {
      const item = node.task ?? node.communication;
      const process = node.processAttempt ? attemptTimes(node.processAttempt) : undefined;
      let end: End = null;
      // Не в конечном статусе — ещё идёт, даже если ждёт в очереди: у родителя такой ребёнок не закончен.
      if (item && TERMINAL.includes(item.state.status)) {
        // Не «??»: null — ещё идёт кто-то из детей, а время изменения — только когда сказать нечего.
        const combined = combineEnds([process?.end, ...childEnds]);
        end = combined === undefined ? item.state.updatedAt : combined;
      }
      result = {
        start: item?.state.createdAt,
        end,
        work: sumWork([...(node.processAttempt ? [attemptWork(node.processAttempt)] : []), ...childWork]),
      };
    }
    times.set(node.key, result);
    return result;
  };
  roots.forEach(visit);
  return times;
}

export function total(units: Record<string, number>): number {
  return Object.values(units).reduce((sum, count) => sum + count, 0);
}

/** По кодовым единицам, а не по правилам языка: время ISO-8601 и метки порядка сравниваются как есть. */
function compare(a: string, b: string): number {
  if (a === b) {
    return 0;
  }
  return a < b ? -1 : 1;
}

/** Сущность дерева в выборе для перезапуска (docs/adr/0169-restart-many.md). */
export interface RestartChoice {
  target: RestartTarget;
  /** Можно ли перезапустить саму сущность — по правилам её уровня. */
  restartable: boolean;
  /** Ключи сущностей-предков в дереве, от ближнего к дальнему. */
  ancestors: string[];
}

/** Почему флажок недоступен: выбран предок, перезапуск только с родителем или нельзя в этом статусе. */
export type RestartHint = 'withParent' | 'onlyWithParent' | 'notRestartable';

/** Сущности дерева с их предками — фазы, попытки и кнопки «Все …» в выбор не входят. */
export function restartChoices(roots: TreeNode[]): Map<string, RestartChoice> {
  const choices = new Map<string, RestartChoice>();
  const walk = (node: TreeNode, ancestors: string[]) => {
    const item = node.task ?? node.communication;
    const isEntity = item !== undefined && node.kind !== 'PHASE' && node.kind !== 'ATTEMPT' && node.kind !== 'MORE';
    if (isEntity) {
      choices.set(node.key, {
        target: { kind: node.kind as RestartKind, id: item.id, executionCreatedAt: item.execution.createdAt },
        restartable: node.communication
          ? communicationRestartable(node.communication)
          : taskRestartable(node.kind as Level, node.task!.state.status),
        ancestors,
      });
    }
    const next = isEntity ? [node.key, ...ancestors] : ancestors;
    node.children.forEach((child) => walk(child, next));
  };
  roots.forEach((root) => walk(root, []));
  return choices;
}

/** Состояние флажка сущности: недоступен — с причиной. */
export function restartBox(
  key: string,
  selected: ReadonlySet<string>,
  choices: Map<string, RestartChoice>,
): { disabled: boolean; hint?: RestartHint } {
  const choice = choices.get(key);
  if (!choice) {
    return { disabled: true };
  }
  if (choice.ancestors.some((ancestor) => selected.has(ancestor))) {
    return { disabled: true, hint: 'withParent' };
  }
  if (!choice.restartable) {
    return { disabled: true, hint: choice.ancestors.length > 0 ? 'onlyWithParent' : 'notRestartable' };
  }
  return { disabled: false };
}

/** Выбор после щелчка: выбранный родитель снимает выбор со своих потомков — они перезапустятся с ним. */
export function toggleRestart(
  selected: ReadonlySet<string>,
  choices: Map<string, RestartChoice>,
  key: string,
  checked: boolean,
): Set<string> {
  const next = new Set(selected);
  if (!checked) {
    next.delete(key);
    return next;
  }
  next.add(key);
  for (const other of next) {
    if (choices.get(other)?.ancestors.includes(key)) {
      next.delete(other);
    }
  }
  return next;
}

/** Что уйдёт на перезапуск: выбранное и доступное сейчас — дерево могло обновиться после выбора. */
export function restartTargets(selected: ReadonlySet<string>, choices: Map<string, RestartChoice>): RestartTarget[] {
  return [...selected]
    .filter((key) => !restartBox(key, selected, choices).disabled)
    .map((key) => choices.get(key)!.target);
}
