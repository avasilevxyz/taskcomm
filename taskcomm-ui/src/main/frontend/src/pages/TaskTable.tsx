import { useInfiniteQuery } from '@tanstack/react-query';
import { Flex, Space, Table } from 'antd';
import { getJson, type Page, type Params, type TaskItem } from '../api';
import {
  IdLink,
  Loadable,
  MoreButton,
  OrphanTag,
  StatusTag,
  taskPath,
  useTime,
  WindowNote,
} from '../common';
import { useI18n } from '../i18n';

const PAGE_SIZE = 50;

/** Список задач страницами по ключу: дочитывается кнопкой, обновляется опросом. */
export function useTaskPages(key: unknown[], params: () => Params, enabled = true) {
  return useInfiniteQuery({
    queryKey: ['tasks', 'list', ...key],
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam }) =>
      getJson<Page<TaskItem>>('/tasks', { ...params(), size: PAGE_SIZE, cursor: pageParam }),
    getNextPageParam: (last) => last.next,
    enabled,
  });
}

export function TaskTable({ pages, showWindow = true }: {
  pages: ReturnType<typeof useTaskPages>;
  showWindow?: boolean;
}) {
  const { t } = useI18n();
  const time = useTime();
  const items = pages.data?.pages.flatMap((page) => page.items) ?? [];
  return (
    <Loadable loading={pages.isLoading} error={pages.error}>
      {() => (
        <Flex vertical gap={8}>
          {showWindow && <WindowNote window={pages.data?.pages[0]?.window} />}
          <Table<TaskItem>
            size="small"
            rowKey="id"
            pagination={false}
            dataSource={items}
            columns={[
              {
                title: t('common.id'),
                dataIndex: 'id',
                render: (id: string, row) => <IdLink to={taskPath(row.level, id, row.execution.createdAt)} id={id} />,
              },
              { title: t('tasks.level'), dataIndex: 'level', render: (level: TaskItem['level']) => t(`level.${level}`) },
              {
                title: t('common.type'),
                dataIndex: 'taskType',
                render: (type: string, row) => (
                  <Space>
                    {type}
                    <OrphanTag orphan={row.state.orphan} />
                  </Space>
                ),
              },
              {
                title: t('common.status'),
                render: (_, row) => <StatusTag status={row.state.status} description={row.state.statusDescription} />,
              },
              { title: t('common.phase'), render: (_, row) => row.state.phase ?? '—' },
              { title: t('common.priority'), render: (_, row) => row.state.priority },
              { title: t('common.created'), render: (_, row) => time(row.state.createdAt) },
              { title: t('common.updated'), render: (_, row) => time(row.state.updatedAt) },
            ]}
          />
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
