import { useInfiniteQuery } from '@tanstack/react-query';
import { Flex, Input, Select, Space, Table, Tag, Typography } from 'antd';
import { useState } from 'react';
import { useSearchParams } from 'react-router';
import { getJson, STATUSES, type CommunicationItem, type Direction, type Page, type Params } from '../api';
import { useMeta } from '../meta';
import {
  communicationPath,
  IdLink,
  Loadable,
  MoreButton,
  OrphanTag,
  StatusTag,
  useTime,
  useWindowChoice,
  WindowNote,
  windowParams,
  WindowSelect,
} from '../common';
import { useI18n } from '../i18n';

const PAGE_SIZE = 50;

export function useCommunicationPages(key: unknown[], params: () => Params) {
  return useInfiniteQuery({
    queryKey: ['communications', 'list', ...key],
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam }) =>
      getJson<Page<CommunicationItem>>('/communications', { ...params(), size: PAGE_SIZE, cursor: pageParam }),
    getNextPageParam: (last) => last.next,
  });
}

export function CommunicationTable({ pages, showWindow = true }: {
  pages: ReturnType<typeof useCommunicationPages>;
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
          <Table<CommunicationItem>
            size="small"
            scroll={{ x: 'max-content' }}
            rowKey="id"
            pagination={false}
            dataSource={items}
            columns={[
              {
                title: t('common.id'),
                dataIndex: 'id',
                render: (id: string, row) => <IdLink to={communicationPath(id, row.execution.createdAt)} id={id} />,
              },
              {
                title: t('communications.direction'),
                dataIndex: 'direction',
                render: (direction: Direction) => t(`direction.${direction}`),
              },
              {
                title: t('communications.system'),
                dataIndex: 'externalSystem',
                render: (system: string, row) => (
                  <Space>
                    {system}
                    <OrphanTag orphan={row.state.orphan} />
                  </Space>
                ),
              },
              {
                title: t('common.status'),
                render: (_, row) => <StatusTag status={row.state.status} description={row.state.statusDescription} />,
              },
              { title: t('common.reason'), ellipsis: true, render: (_, row) => row.state.statusDescription ?? '—' },
              { title: t('common.phase'), render: (_, row) => row.state.phase },
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

export function Communications() {
  const { t } = useI18n();
  const meta = useMeta();
  const [search, setSearch] = useSearchParams();
  const [choice, setChoice] = useWindowChoice();
  const directionParam = search.get('direction');
  const direction = directionParam === 'INBOUND' || directionParam === 'OUTBOUND' ? directionParam : undefined;
  const system = search.get('system') ?? undefined;
  const statuses = search.getAll('status');
  const requestId = search.get('requestId') ?? undefined;
  const executionId = search.get('executionId') ?? undefined;
  const executionCreatedAt = search.get('executionCreatedAt') ?? undefined;
  const [draft, setDraft] = useState(requestId ?? '');

  const pages = useCommunicationPages(
    [direction, system, statuses, requestId, executionId, executionCreatedAt, choice],
    () => ({
      direction,
      system,
      status: statuses,
      requestId,
      executionId,
      executionCreatedAt,
      ...windowParams(choice),
    }),
  );

  const update = (changes: Record<string, string | string[] | undefined>) => {
    const copy = new URLSearchParams(search);
    for (const [name, value] of Object.entries(changes)) {
      copy.delete(name);
      if (Array.isArray(value)) {
        value.forEach((item) => copy.append(name, item));
      } else if (value !== undefined && value !== '') {
        copy.set(name, value);
      }
    }
    setSearch(copy, { replace: true });
  };

  return (
    <Flex vertical gap={12}>
      <Typography.Title level={3}>{t('communications.title')}</Typography.Title>
      <div className="toolbar">
        <Select
          allowClear
          placeholder={t('communications.direction')}
          style={{ minWidth: 160 }}
          value={direction}
          onChange={(value?: string) => update({ direction: value })}
          options={(['INBOUND', 'OUTBOUND'] as const).map((value) => ({ value, label: t(`direction.${value}`) }))}
        />
        <Select
          allowClear
          showSearch
          placeholder={t('communications.system')}
          style={{ minWidth: 180 }}
          value={system}
          onChange={(value?: string) => update({ system: value })}
          options={(meta.data?.communications?.systems ?? []).map((item) => ({ value: item.system, label: item.system }))}
        />
        <Select
          mode="multiple"
          allowClear
          placeholder={t('common.status')}
          style={{ minWidth: 220 }}
          value={statuses}
          onChange={(value: string[]) => update({ status: value })}
          options={STATUSES.COMMUNICATION.map((status) => ({ value: status, label: status }))}
        />
        <WindowSelect value={choice} onChange={setChoice} searchWindow={meta.data?.searchWindow} />
        <Input.Search
          allowClear
          placeholder={t('communications.requestId')}
          style={{ width: 260 }}
          value={draft}
          onChange={(event) => setDraft(event.target.value)}
          onSearch={(value) => update({ requestId: value })}
        />
        {executionId && (
          <Tag closable onClose={() => update({ executionId: undefined, executionCreatedAt: undefined })}>
            {t('tasks.inExecution', { id: executionId })}
          </Tag>
        )}
      </div>
      <CommunicationTable pages={pages} showWindow={executionId === undefined} />
    </Flex>
  );
}
