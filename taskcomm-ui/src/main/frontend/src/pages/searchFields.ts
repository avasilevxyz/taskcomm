import { useQuery } from '@tanstack/react-query';
import { getJson, type Level, type SearchField } from '../api';

/** Реестр полей поиска задач: меняется только со стартом экземпляров, поэтому без опроса. */
export function useSearchFields() {
  return useQuery({
    queryKey: ['tasks', 'search-fields'],
    queryFn: () => getJson<SearchField[]>('/tasks/search-fields'),
    refetchInterval: false,
  });
}

/** Колонка таблицы по полю поиска: одна на имя, со всеми путями, под которыми его объявляли. */
export interface FieldColumn {
  name: string;
  title: string;
  description?: string;
  paths: string[];
}

/**
 * Колонки полей поиска для таблицы одного уровня и одного типа. Берутся все объявления реестра,
 * как у поиска, в том числе убранные из кода; поле с несколькими путями — одна колонка.
 */
export function fieldColumns(fields: SearchField[], level: Level, type: string): FieldColumn[] {
  const byName = new Map<string, FieldColumn>();
  for (const field of fields) {
    if (field.level !== level || field.taskType !== type) {
      continue;
    }
    const known = byName.get(field.name);
    if (known) {
      if (!known.paths.includes(field.jsonPath)) {
        known.paths.push(field.jsonPath);
      }
      known.description ??= field.description ?? undefined;
      continue;
    }
    byName.set(field.name, {
      name: field.name,
      title: field.title || field.name,
      description: field.description ?? undefined,
      paths: [field.jsonPath],
    });
  }
  return [...byName.values()].sort((a, b) => a.title.localeCompare(b.title));
}

/** Значение по пути реестра: `$` и имена через точку, без массивов и кавычек. */
export function valueAt(payload: unknown, path: string): unknown {
  let current = payload;
  for (const name of path.split('.').slice(1)) {
    if (current === null || typeof current !== 'object') {
      return undefined;
    }
    current = (current as Record<string, unknown>)[name];
  }
  return current;
}

/** Значение колонки: первое непустое по её путям. */
export function fieldValue(payload: unknown, column: FieldColumn): unknown {
  for (const path of column.paths) {
    const value = valueAt(payload, path);
    if (value !== undefined && value !== null) {
      return value;
    }
  }
  return undefined;
}

/** Значение для ячейки: число и строка как есть, объект — JSON, пустое — прочерк. */
export function cellText(value: unknown): string {
  if (value === undefined || value === null || value === '') {
    return '—';
  }
  return typeof value === 'object' ? JSON.stringify(value) : String(value);
}

/** Поле поиска в списке выбора: одно на имя, сколько бы типов его ни объявили. */
export interface FieldOption {
  value: string;
  label: string;
  description?: string;
}

/** Группа списка: поля одного типа или общие для нескольких. */
export interface FieldGroup {
  /** Тип задачи; {@code undefined} — поля нескольких типов. */
  taskType?: string;
  options: FieldOption[];
}

/**
 * Поля поиска уровня, разложенные для выбора. С выбранным типом — одна группа его полей.
 * Без типа — по группе на тип, а поле, объявленное у нескольких типов, — один раз в общей группе
 * впереди: поиск по имени поля ищет по всем типам сразу.
 */
export function fieldGroups(fields: SearchField[], level: Level, type?: string): FieldGroup[] {
  const byName = new Map<string, { option: FieldOption; types: Set<string> }>();
  for (const field of fields) {
    if (field.level !== level || (type !== undefined && field.taskType !== type)) {
      continue;
    }
    const known = byName.get(field.name);
    if (known) {
      known.types.add(field.taskType);
      known.option.description ??= field.description ?? undefined;
      continue;
    }
    byName.set(field.name, {
      option: {
        value: field.name,
        label: field.title ? `${field.title} (${field.name})` : field.name,
        description: field.description ?? undefined,
      },
      types: new Set([field.taskType]),
    });
  }
  const shared: FieldOption[] = [];
  const own = new Map<string, FieldOption[]>();
  for (const { option, types } of byName.values()) {
    if (types.size > 1) {
      shared.push(option);
    } else {
      const [taskType] = types;
      own.set(taskType, [...(own.get(taskType) ?? []), option]);
    }
  }
  const byLabel = (a: FieldOption, b: FieldOption) => a.label.localeCompare(b.label);
  const groups: FieldGroup[] = [...own.entries()]
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([taskType, options]) => ({ taskType, options: options.sort(byLabel) }));
  if (type !== undefined) {
    return [{ taskType: type, options: [...shared, ...groups.flatMap((group) => group.options)].sort(byLabel) }];
  }
  return shared.length > 0 ? [{ options: shared.sort(byLabel) }, ...groups] : groups;
}
