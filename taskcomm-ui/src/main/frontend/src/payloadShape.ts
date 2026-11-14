import { jsonLanguage } from '@codemirror/lang-json';
import type { PayloadShape, ShapeKind } from './api';

// Шаблон и проверка нагрузки по структуре из REST (docs/adr/0160-payload-shape.md). Это подсказка,
// а не схема: последнее слово — за разбором на сервере при создании.

/** Заглушка по виду значения: ключи объекта все, значения пустые своего типа. */
export function template(shape: PayloadShape): unknown {
  switch (shape.kind) {
    case 'STRING':
      return '';
    case 'INTEGER':
    case 'NUMBER':
      return 0;
    case 'BOOLEAN':
      return false;
    case 'ARRAY':
      return [];
    case 'MAP':
      return {};
    case 'OBJECT':
      return Object.fromEntries(Object.entries(shape.properties ?? {}).map(([key, value]) => [key, template(value)]));
    default:
      return null;
  }
}

/** Шаблон текстом — так, как его покажет редактор. */
export function templateText(shape: PayloadShape): string {
  return JSON.stringify(template(shape), null, 2);
}

/** Замечание к месту в тексте. */
export type ShapeProblem = { from: number; to: number } & (
  | { code: 'unknownKey'; key: string }
  | { code: 'missingKeys'; keys: string[] }
  | { code: 'wrongType'; expected: ShapeKind }
  | { code: 'notOneOf'; values: string[] }
);

type Node = ReturnType<typeof jsonLanguage.parser.parse>['topNode'];

const VALUES = new Set(['Object', 'Array', 'String', 'Number', 'True', 'False', 'Null']);

const NODES: Partial<Record<ShapeKind, string[]>> = {
  STRING: ['String'],
  INTEGER: ['Number'],
  NUMBER: ['Number'],
  BOOLEAN: ['True', 'False'],
  OBJECT: ['Object'],
  MAP: ['Object'],
  ARRAY: ['Array'],
};

/**
 * Расхождения текста со структурой. Ошибки синтаксиса не здесь — их показывает разбор JSON;
 * {@code null} подходит любому виду: маппер его принимает.
 */
export function checkShape(text: string, shape: PayloadShape): ShapeProblem[] {
  const root = jsonLanguage.parser.parse(text).topNode.firstChild;
  const problems: ShapeProblem[] = [];
  if (root && VALUES.has(root.name)) {
    visit(root, shape, text, problems);
  }
  return problems;
}

function children(node: Node): Node[] {
  const result: Node[] = [];
  for (let child = node.firstChild; child; child = child.nextSibling) {
    result.push(child);
  }
  return result;
}

function parsed(text: string, node: Node): unknown {
  try {
    return JSON.parse(text.slice(node.from, node.to));
  } catch {
    return undefined;
  }
}

function visit(node: Node, shape: PayloadShape, text: string, out: ShapeProblem[]): void {
  const allowed = NODES[shape.kind];
  if (!allowed || node.name === 'Null') {
    return;
  }
  if (!allowed.includes(node.name) || (shape.kind === 'INTEGER' && /[.eE]/.test(text.slice(node.from, node.to)))) {
    out.push({ from: node.from, to: node.to, code: 'wrongType', expected: shape.kind });
    return;
  }
  if (shape.kind === 'STRING' && shape.values?.length) {
    const value = parsed(text, node);
    if (typeof value === 'string' && !shape.values.includes(value)) {
      out.push({ from: node.from, to: node.to, code: 'notOneOf', values: shape.values });
    }
  }
  if (shape.kind === 'ARRAY' && shape.items) {
    children(node).filter((child) => VALUES.has(child.name)).forEach((child) => visit(child, shape.items!, text, out));
  }
  if (shape.kind === 'OBJECT' || shape.kind === 'MAP') {
    visitObject(node, shape, text, out);
  }
}

function visitObject(node: Node, shape: PayloadShape, text: string, out: ShapeProblem[]): void {
  const seen = new Set<string>();
  for (const property of children(node).filter((child) => child.name === 'Property')) {
    const name = property.getChild('PropertyName');
    const value = children(property).find((child) => VALUES.has(child.name));
    const key = name ? parsed(text, name) : undefined;
    if (typeof key !== 'string') {
      continue;
    }
    seen.add(key);
    const expected = shape.kind === 'MAP' ? shape.items : shape.properties?.[key];
    if (!expected && shape.kind === 'OBJECT') {
      out.push({ from: name!.from, to: name!.to, code: 'unknownKey', key });
    } else if (expected && value) {
      visit(value, expected, text, out);
    }
  }
  if (shape.kind === 'OBJECT') {
    const missing = Object.keys(shape.properties ?? {}).filter((key) => !seen.has(key));
    if (missing.length > 0) {
      out.push({ from: node.from, to: node.from + 1, code: 'missingKeys', keys: missing });
    }
  }
}

/** Пуст ли текст по существу: ничего, кроме пустого объекта, — тогда шаблон его заменит. */
export function blank(text: string): boolean {
  return text.replace(/\s/g, '') === '' || text.replace(/\s/g, '') === '{}';
}
