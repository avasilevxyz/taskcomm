import { ArrowDownOutlined, ArrowUpOutlined, HolderOutlined, SettingOutlined } from '@ant-design/icons';
import { Button, Checkbox, Flex, Table, Tooltip, type TableProps } from 'antd';
import type { ColumnType } from 'antd/es/table';
import type { SorterResult, SortOrder } from 'antd/es/table/interface';
import { createContext, useContext, useState, useSyncExternalStore, type ReactNode } from 'react';
import { createPortal } from 'react-dom';
import { useI18n } from './i18n';
import { TableCurlButton, type TableRequest } from './curlPanel';
import { TableSqlButton } from './sqlPanel';
import { useOpenPanel } from './panels';

// Таблица интерфейса: сортировка по нескольким столбцам и, если у таблицы есть имя настроек,
// порядок и видимость столбцов, которые пользователь задаёт в боковой панели.
//
// Сортировка. Ant Design держит приоритет номером на столбце, а здесь он — порядок щелчков: первый
// выбранный столбец сортирует первым, следующий — внутри равных и так далее. Щелчок по столбцу меняет
// направление по кругу «по возрастанию → по убыванию → без сортировки», не снимая остальных.
//
// Настройки столбцов живут в браузере, у каждой таблицы свои: это удобство одного человека,
// а не общее состояние. Столбец, которого в настройках нет, — например, поле поиска нового типа, —
// стоит в конце и виден.

/** Один столбец в порядке сортировки. */
export interface SortKey {
  key: string;
  order: 'ascend' | 'descend';
}

/** Настройки столбцов таблицы: порядок и скрытые. */
export interface ColumnSettings {
  order: string[];
  hidden: string[];
}

type Compare<TRow> = (left: TRow, right: TRow) => number;

const EMPTY: ColumnSettings = { order: [], hidden: [] };

function columnKey<TRow>(column: ColumnType<TRow>, index: number): string {
  if (column.key !== undefined) {
    return String(column.key);
  }
  if (column.dataIndex !== undefined) {
    return Array.isArray(column.dataIndex) ? column.dataIndex.join('.') : String(column.dataIndex);
  }
  return `#${index}`;
}

/**
 * Новый порядок сортировки по ответу таблицы: столбцы, которые остались, — на своих местах,
 * новый — в конец, снятые — долой.
 */
export function nextSort(current: SortKey[], states: { key: string; order: SortOrder | undefined }[]): SortKey[] {
  const active = new Map(
    states.filter((state) => state.order === 'ascend' || state.order === 'descend').map((state) => [state.key, state.order]),
  );
  const kept = current
    .filter((sort) => active.has(sort.key))
    .map((sort) => ({ key: sort.key, order: active.get(sort.key) as SortKey['order'] }));
  const added = [...active.entries()]
    .filter(([key]) => !current.some((sort) => sort.key === key))
    .map(([key, order]) => ({ key, order: order as SortKey['order'] }));
  return [...kept, ...added];
}

/** Все столбцы в порядке настроек: известные — как сохранены, новые — следом в своём порядке. */
export function arrange(keys: string[], settings: ColumnSettings): string[] {
  const known = settings.order.filter((key) => keys.includes(key));
  return [...known, ...keys.filter((key) => !known.includes(key))];
}

/** Видимые столбцы в порядке настроек; хотя бы один остаётся всегда. */
export function visible(keys: string[], settings: ColumnSettings): string[] {
  const shown = arrange(keys, settings).filter((key) => !settings.hidden.includes(key));
  return shown.length > 0 ? shown : arrange(keys, settings).slice(0, 1);
}

/** Столбец {@code key} на место {@code target} в полном порядке. */
export function moved(order: string[], key: string, target: number): string[] {
  const rest = order.filter((item) => item !== key);
  const place = Math.max(0, Math.min(target, rest.length));
  return [...rest.slice(0, place), key, ...rest.slice(place)];
}

const STORAGE_PREFIX = 'taskcomm.columns.';

function load(name: string): ColumnSettings {
  try {
    const stored = JSON.parse(window.localStorage.getItem(STORAGE_PREFIX + name) ?? 'null') as ColumnSettings | null;
    return stored && Array.isArray(stored.order) && Array.isArray(stored.hidden) ? stored : EMPTY;
  } catch {
    return EMPTY;
  }
}

function save(name: string, settings: ColumnSettings): void {
  try {
    window.localStorage.setItem(STORAGE_PREFIX + name, JSON.stringify(settings));
  } catch {
    // не сохранилось — настройки действуют до перезагрузки
  }
}

/**
 * Место для кнопки «Столбцы» в строке, которая над таблицей уже есть: у списка — рядом с окном
 * поиска, у попыток — в заголовке карточки, у вкладок — рядом с ними. {@code undefined} — места нет,
 * кнопка встаёт своей строкой; {@code null} — место есть, но не сейчас: не отрисовано или вкладка
 * не активна, и кнопки нет.
 */
const SlotContext = createContext<HTMLElement | null | undefined>(undefined);

/** Место для кнопки: элемент, который ставится в строку, и его узел — для {@link ColumnsSlotProvider}. */
export function useColumnsSlot(): [ReactNode, HTMLElement | null] {
  const [node, setNode] = useState<HTMLElement | null>(null);
  return [<span key="columns-slot" className="columns-slot" ref={setNode} />, node];
}

/**
 * Таблицы внутри кладут кнопку «Столбцы» в это место. Выключенный — не трогает место, которое дал
 * кто-то выше: вложенная таблица во вкладке пользуется местом вкладок.
 */
export function ColumnsSlotProvider({ node, enabled = true, children }: {
  node: HTMLElement | null;
  enabled?: boolean;
  children: ReactNode;
}) {
  return enabled ? <SlotContext.Provider value={node}>{children}</SlotContext.Provider> : <>{children}</>;
}

// Настройки по имени таблицы — общие для таблицы и панели «Столбцы»: панель рендерится в стопке,
// не внутри таблицы, и обе должны видеть одно и то же.
const current = new Map<string, ColumnSettings>();
const listeners = new Map<string, Set<() => void>>();

function settingsOf(name: string): ColumnSettings {
  if (!current.has(name)) {
    current.set(name, load(name));
  }
  return current.get(name) as ColumnSettings;
}

function changeSettings(name: string, settings: ColumnSettings): void {
  current.set(name, settings);
  save(name, settings);
  listeners.get(name)?.forEach((listener) => listener());
}

/** Настройки столбцов таблицы; без имени — пустые и не меняются. */
function useColumnSettings(name: string | undefined): ColumnSettings {
  return useSyncExternalStore(
    (listener) => {
      if (!name) {
        return () => undefined;
      }
      const set = listeners.get(name) ?? new Set();
      set.add(listener);
      listeners.set(name, set);
      return () => set.delete(listener);
    },
    () => (name ? settingsOf(name) : EMPTY),
  );
}

function priorityTitle(title: ReactNode, priority: number): ReactNode {
  return (
    <span>
      {title}
      <sup className="sort-priority">{priority}</sup>
    </span>
  );
}

/**
 * Таблица Ant Design с сортировкой по нескольким столбцам в порядке щелчков. С {@code settings} —
 * именем настроек — над таблицей кнопка, которая открывает порядок и видимость столбцов в боковой
 * панели стопки, как карточки и форма создания. С {@code request} — рядом значки «SQL» и «curl»: запрос
 * к базе и команда, которыми таблица получила данные.
 */
export function DataTable<TRow extends object>({
  settings: name,
  request,
  ...props
}: TableProps<TRow> & { settings?: string; request?: TableRequest }) {
  const [sorts, setSorts] = useState<SortKey[]>([]);
  const settings = useColumnSettings(name);
  const slot = useContext(SlotContext);

  const all = (props.columns ?? []).map((raw, index) => {
    const column = raw as ColumnType<TRow>;
    return { key: columnKey(column, index), column };
  });
  const keys = all.map((item) => item.key);
  const shown = name ? visible(keys, settings) : keys;
  const columns = shown
    .map((key) => all.find((item) => item.key === key)!)
    .map(({ key, column }) => {
      if (typeof column.sorter !== 'function') {
        return { ...column, key };
      }
      const position = sorts.findIndex((sort) => sort.key === key);
      return {
        ...column,
        key,
        // Больший multiple сортирует раньше: первый в порядке щелчков получает наибольший.
        sorter: { compare: column.sorter as Compare<TRow>, multiple: position >= 0 ? sorts.length - position : 0 },
        sortOrder: position >= 0 ? sorts[position].order : null,
        title:
          sorts.length > 1 && position >= 0 && typeof column.title !== 'function'
            ? priorityTitle(column.title, position + 1)
            : column.title,
      };
    });

  const table = (
    <Table<TRow>
      // Подсказка «нажмите для сортировки» над заголовком мешает читать таблицу: стрелки и так видны.
      showSorterTooltip={false}
      {...props}
      columns={columns}
      onChange={(pagination, filters, sorter, extra) => {
        const results = (Array.isArray(sorter) ? sorter : [sorter]) as SorterResult<TRow>[];
        setSorts((current) =>
          nextSort(
            current,
            results.map((result) => ({ key: String(result.columnKey ?? ''), order: result.order ?? undefined })),
          ),
        );
        props.onChange?.(pagination, filters, sorter, extra);
      }}
    />
  );
  if (!name) {
    return table;
  }
  const button = (
    <>
      {request && <TableSqlButton request={request} />}
      {request && <TableCurlButton request={request} />}
      <ColumnsButton
      name={name}
      columns={all.map(({ key, column }) => ({
        key,
        title: typeof column.title === 'function' ? key : (column.title ?? key),
      }))}
    />
    </>
  );
  if (slot === undefined) {
    // Строки над таблицей нет — кнопка встаёт своей строкой справа.
    return (
      <Flex vertical gap={4}>
        <Flex justify="end">{button}</Flex>
        {table}
      </Flex>
    );
  }
  return (
    <>
      {slot && createPortal(button, slot)}
      {table}
    </>
  );
}

/** Кнопка над таблицей: открывает её столбцы в боковой панели стопки. */
function ColumnsButton({ name, columns }: { name: string; columns: { key: string; title: ReactNode }[] }) {
  const { t } = useI18n();
  const openPanel = useOpenPanel();
  const onClick = () =>
    openPanel({
      kind: 'columns',
      label: t('table.columns'),
      render: () => <ColumnsPanel name={name} columns={columns} />,
    });
  return (
    <Tooltip title={t('table.columns')}>
      <Button size="small" type="text" icon={<SettingOutlined />} aria-label={t('table.columns')} onClick={onClick} />
    </Tooltip>
  );
}

/** Порядок и видимость столбцов: перетаскиванием или стрелками, флажок — показывать ли. */
function ColumnsPanel({ name, columns }: { name: string; columns: { key: string; title: ReactNode }[] }) {
  const { t } = useI18n();
  const settings = useColumnSettings(name);
  const onChange = (next: ColumnSettings) => changeSettings(name, next);
  const [dragged, setDragged] = useState<string>();
  // Куда упадёт столбец: ключ строки или край списка — для подсветки.
  const [over, setOver] = useState<string>();
  const keys = columns.map((column) => column.key);
  const order = arrange(keys, settings);
  const shown = visible(keys, settings);
  const move = (key: string, target: number) => onChange({ ...settings, order: moved(order, key, target) });
  const finish = () => {
    setDragged(undefined);
    setOver(undefined);
  };
  // Края списка — отдельные зоны в полную высоту строки: попасть точно над первой строкой или под
  // последней неудобно. Видны только во время перетаскивания.
  const edge = (place: 'start' | 'end') =>
    dragged !== undefined && (
      <div
        className={`column-drop${over === place ? ' column-drop-over' : ''}`}
        onDragOver={(event) => {
          event.preventDefault();
          setOver(place);
        }}
        onDragLeave={() => setOver(undefined)}
        onDrop={(event) => {
          event.preventDefault();
          move(dragged, place === 'start' ? 0 : order.length);
          finish();
        }}
      >
        {place === 'start' ? t('table.dropStart') : t('table.dropEnd')}
      </div>
    );
  const toggle = (key: string, show: boolean) =>
    onChange({
      ...settings,
      order,
      hidden: show ? settings.hidden.filter((item) => item !== key) : [...settings.hidden, key],
    });
  return (
    <Flex vertical gap={16}>
      <Flex vertical gap={2}>
        {edge('start')}
        {order.map((key, index) => {
          const column = columns.find((item) => item.key === key)!;
          const isShown = shown.includes(key);
          return (
            <div
              key={key}
              className={`column-setting${dragged === key ? ' column-setting-dragged' : ''}${
                over === key && dragged !== key ? ' column-setting-over' : ''
              }`}
              draggable
              onDragStart={(event) => {
                // Без данных Firefox не начинает перетаскивание.
                event.dataTransfer.setData('text/plain', key);
                event.dataTransfer.effectAllowed = 'move';
                // Зоны краёв меняют раскладку; сразу в dragstart Chrome от этого отменяет перетаскивание.
                window.setTimeout(() => setDragged(key));
              }}
              onDragEnd={finish}
              onDragOver={(event) => {
                event.preventDefault();
                setOver(key);
              }}
              onDrop={(event) => {
                event.preventDefault();
                if (dragged && dragged !== key) {
                  move(dragged, index);
                }
                finish();
              }}
            >
              <HolderOutlined className="column-setting-handle" />
              <Checkbox
                checked={isShown}
                // Последний видимый столбец не скрыть: таблица без столбцов бессмысленна.
                disabled={isShown && shown.length === 1}
                onChange={(event) => toggle(key, event.target.checked)}
              >
                {column.title}
              </Checkbox>
              <span className="column-setting-arrows">
                <Button
                  size="small"
                  type="text"
                  icon={<ArrowUpOutlined />}
                  aria-label={t('table.moveUp')}
                  disabled={index === 0}
                  onClick={() => move(key, index - 1)}
                />
                <Button
                  size="small"
                  type="text"
                  icon={<ArrowDownOutlined />}
                  aria-label={t('table.moveDown')}
                  disabled={index === order.length - 1}
                  onClick={() => move(key, index + 1)}
                />
              </span>
            </div>
          );
        })}
        {edge('end')}
      </Flex>
      <Button
        style={{ alignSelf: 'flex-start' }}
        onClick={() => onChange(EMPTY)}
        disabled={settings.order.length === 0 && settings.hidden.length === 0}
      >
        {t('table.reset')}
      </Button>
    </Flex>
  );
}
