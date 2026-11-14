import { useInfiniteQuery } from '@tanstack/react-query';
import { Flex, Space, Tooltip } from 'antd';
import { getJson, type Level, type Page, type Params, type TaskItem } from '../api';
import {
  IdLink,
  Loadable,
  MoreButton,
  OrphanTag,
  Reason,
  PhaseLabel,
  ProcessorTitle,
  StatusTag,
  by,
  taskPath,
  useTime,
  WindowNote,
} from '../common';
import { rowOpens, useEntityPanel } from '../entityPanel';
import { ColumnsSlotProvider, DataTable, useColumnsSlot } from '../dataTable';
import { useI18n } from '../i18n';
import { cellText, fieldColumns, fieldValue, useSearchFields } from './searchFields';

const PAGE_SIZE = 50;

/** Значение поля для сортировки: число — числом, остальное — текстом. */
function sortable(value: unknown): string | number | undefined {
  if (typeof value === 'number') {
    return value;
  }
  return value === undefined || value === null ? undefined : cellText(value);
}

/** Список задач страницами по ключу: дочитывается кнопкой, обновляется опросом. */
export function useTaskPages(key: unknown[], params: () => Params, enabled = true) {
  const pages = useInfiniteQuery({
    queryKey: ['tasks', 'list', ...key],
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam }) =>
      getJson<Page<TaskItem>>('/tasks', { ...params(), size: PAGE_SIZE, cursor: pageParam }),
    getNextPageParam: (last) => last.next,
    enabled,
  });
  // Запрос первой страницы — для команды curl над таблицей.
  return { ...pages, request: { path: '/tasks', params: { ...params(), size: PAGE_SIZE } } };
}

/**
 * Таблица задач. Когда уровень и тип строк известны, в конце — колонки полей поиска этого типа
 * со значениями из нагрузки; без типа их нет: у разных типов поля разные.
 */
export function TaskTable({ pages, showWindow = true, level, taskType }: {
  pages: ReturnType<typeof useTaskPages>;
  showWindow?: boolean;
  level?: Level;
  taskType?: string;
}) {
  const { t } = useI18n();
  const time = useTime();
  const fields = useSearchFields();
  const extra = level && taskType ? fieldColumns(fields.data ?? [], level, taskType) : [];
  const items = pages.data?.pages.flatMap((page) => page.items) ?? [];
  const open = useEntityPanel();
  const [slot, slotNode] = useColumnsSlot();
  return (
    <Loadable loading={pages.isLoading} error={pages.error}>
      {() => (
        <Flex vertical gap={8}>
          {showWindow && (
            <Flex justify="space-between" align="center" gap={8}>
              <WindowNote window={pages.data?.pages[0]?.window} />
              {slot}
            </Flex>
          )}
          <ColumnsSlotProvider node={slotNode} enabled={showWindow}>
          <DataTable<TaskItem>
            settings="tasks"
            request={pages.request}
            size="small"
            scroll={{ x: 'max-content' }}
            rowKey="id"
            onRow={(row) =>
              rowOpens(() => open({ kind: 'task', level: row.level, id: row.id, createdAt: row.execution.createdAt }))
            }
            pagination={false}
            dataSource={items}
            columns={[
              {
                title: t('common.id'),
                sorter: by((row: TaskItem) => row.id),
                dataIndex: 'id',
                render: (id: string, row) => <IdLink to={taskPath(row.level, id, row.execution.createdAt)} id={id} />,
              },
              { title: t('tasks.level'), sorter: by((row: TaskItem) => row.level), dataIndex: 'level', render: (level: TaskItem['level']) => t(`level.${level}`) },
              {
                title: t('common.type'),
                sorter: by((row: TaskItem) => row.taskType),
                dataIndex: 'taskType',
                render: (type: string, row) => (
                  <Space>
                    <ProcessorTitle kind="task" id={type} />
                    <OrphanTag orphan={row.state.orphan} />
                  </Space>
                ),
              },
              {
                key: 'common.status',
                title: t('common.status'),
                sorter: by((row: TaskItem) => row.state.status),
                render: (_, row) => <StatusTag status={row.state.status} description={row.state.statusDescription} />,
              },
              { key: 'common.reason', title: t('common.reason'), sorter: by((row: TaskItem) => row.state.statusDescription), render: (_, row) => <Reason text={row.state.statusDescription} /> },
              { key: 'common.phase', title: t('common.phase'), sorter: by((row: TaskItem) => row.state.phase), render: (_, row) => <PhaseLabel phase={row.state.phase} /> },
              { key: 'common.priority', title: t('common.priority'), sorter: by((row: TaskItem) => row.state.priority), render: (_, row) => row.state.priority },
              { key: 'common.created', title: t('common.created'), sorter: by((row: TaskItem) => row.state.createdAt), render: (_, row) => time(row.state.createdAt) },
              { key: 'common.updated', title: t('common.updated'), sorter: by((row: TaskItem) => row.state.updatedAt), render: (_, row) => time(row.state.updatedAt) },
              ...extra.map((column) => ({
                key: `field.${column.name}`,
                title: column.description ? <Tooltip title={column.description}>{column.title}</Tooltip> : column.title,
                sorter: by((row: TaskItem) => sortable(fieldValue(row.payload, column))),
                render: (_: unknown, row: TaskItem) => cellText(fieldValue(row.payload, column)),
              })),
            ]}
          />
          </ColumnsSlotProvider>
          <MoreButton
            visible={pages.hasNextPage}
            loading={pages.isFetchingNextPage}
            onClick={() => void pages.fetchNextPage()}
          />
        </Flex>
      )}
    </Loadable>
  );
}
