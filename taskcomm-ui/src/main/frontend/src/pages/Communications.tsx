import { useInfiniteQuery } from '@tanstack/react-query';
import { Flex, Input, Select, Space, Table, Tag, Tooltip, Typography } from 'antd';
import { useState } from 'react';
import { useSearchParams } from 'react-router';
import { getJson, STATUSES, type CommunicationItem, type Direction, type Page, type Params } from '../api';
import { useMeta } from '../meta';
import {
  communicationPath,
  IdLink,
  Loadable,
  MoreButton,
  NO_AUTOFILL,
  OrphanTag,
  PhaseLabel,
  processorOption,
  ProcessorTitle,
  StatusTag,
  by,
  useStatusOptions,
  useTime,
  useWindowChoice,
  WindowNote,
  windowParams,
  WindowSelect,
} from '../common';
import { rowOpens, useEntityDrawer } from '../entityDrawer';
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
  const open = useEntityDrawer();
  return (
    <Loadable loading={pages.isLoading} error={pages.error}>
      {() => (
        <Flex vertical gap={8}>
          {showWindow && <WindowNote window={pages.data?.pages[0]?.window} />}
          <Table<CommunicationItem>
            size="small"
            scroll={{ x: 'max-content' }}
            rowKey="id"
            onRow={(row) =>
              rowOpens(() => open({ kind: 'communication', id: row.id, createdAt: row.execution.createdAt }))
            }
            pagination={false}
            dataSource={items}
            columns={[
              {
                title: t('common.id'),
                sorter: by((row: CommunicationItem) => row.id),
                dataIndex: 'id',
                render: (id: string, row) => <IdLink to={communicationPath(id, row.execution.createdAt)} id={id} />,
              },
              {
                title: t('communications.direction'),
                sorter: by((row: CommunicationItem) => row.direction),
                dataIndex: 'direction',
                render: (direction: Direction) => t(`direction.${direction}`),
              },
              {
                title: t('communications.system'),
                sorter: by((row: CommunicationItem) => row.externalSystem),
                dataIndex: 'externalSystem',
                render: (system: string, row) => (
                  <Space>
                    <ProcessorTitle kind="system" id={system} />
                    <OrphanTag orphan={row.state.orphan} />
                  </Space>
                ),
              },
              {
                title: t('common.status'),
                sorter: by((row: CommunicationItem) => row.state.status),
                render: (_, row) => <StatusTag status={row.state.status} description={row.state.statusDescription} />,
              },
              { title: t('common.reason'), sorter: by((row: CommunicationItem) => row.state.statusDescription), ellipsis: true, render: (_, row) => row.state.statusDescription ?? '—' },
              { title: t('common.phase'), sorter: by((row: CommunicationItem) => row.state.phase), render: (_, row) => <PhaseLabel phase={row.state.phase} /> },
              { title: t('common.created'), sorter: by((row: CommunicationItem) => row.state.createdAt), render: (_, row) => time(row.state.createdAt) },
              { title: t('common.updated'), sorter: by((row: CommunicationItem) => row.state.updatedAt), render: (_, row) => time(row.state.updatedAt) },
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
  const statusOptions = useStatusOptions();
  const [search, setSearch] = useSearchParams();
  const [choice, setChoice] = useWindowChoice();
  const directionParam = search.get('direction');
  const direction = directionParam === 'INBOUND' || directionParam === 'OUTBOUND' ? directionParam : undefined;
  const system = search.get('system') ?? undefined;
  const statuses = search.getAll('status');
  const requestId = search.get('requestId') ?? undefined;
  const executionId = search.get('executionId') ?? undefined;
  const executionCreatedAt = search.get('executionCreatedAt') ?? undefined;
  const dedupKey = search.get('dedupKey') ?? undefined;
  const dedupSubject = search.get('dedupSubject') ?? undefined;
  const correlationSystem = search.get('correlationSystem') ?? undefined;
  const correlationId = search.get('correlationId') ?? undefined;
  const [draft, setDraft] = useState(requestId ?? '');

  const pages = useCommunicationPages(
    [direction, system, statuses, requestId, executionId, executionCreatedAt, dedupKey, dedupSubject, correlationSystem, correlationId, choice],
    () => ({
      direction,
      system,
      status: statuses,
      requestId,
      executionId,
      executionCreatedAt,
      dedupKey,
      dedupSubject,
      correlationSystem,
      correlationId,
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
        <Tooltip title={t('communications.direction')}>
          <Select
            allowClear
            placeholder={t('communications.direction')}
            style={{ minWidth: 160 }}
            value={direction}
            onChange={(value?: string) => update({ direction: value })}
            options={(['INBOUND', 'OUTBOUND'] as const).map((value) => ({ value, label: t(`direction.${value}`) }))}
          />
        </Tooltip>
        <Tooltip title={t('communications.system')}>
          <Select
            allowClear
            showSearch={{ optionFilterProp: 'label' }}
            placeholder={t('communications.system')}
            style={{ minWidth: 180 }}
            value={system}
            onChange={(value?: string) => update({ system: value })}
            options={(meta.data?.communications?.systems ?? []).map((item) => processorOption({ id: item.system, title: item.title }))}
          />
        </Tooltip>
        <Tooltip title={t('common.status')}>
          <Select
            mode="multiple"
            allowClear
            placeholder={t('common.status')}
            style={{ minWidth: 220 }}
            value={statuses}
            onChange={(value: string[]) => update({ status: value })}
            options={statusOptions(STATUSES.COMMUNICATION)}
          />
        </Tooltip>
        <WindowSelect value={choice} onChange={setChoice} searchWindow={meta.data?.searchWindow} />
        <Input.Search
          allowClear
          autoComplete={NO_AUTOFILL}
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
        {dedupKey && (
          <Tag closable onClose={() => update({ dedupKey: undefined, dedupSubject: undefined })}>
            {dedupSubject
              ? t('communications.byDedupSubjectKey', { subject: dedupSubject, key: dedupKey })
              : t('communications.byDedupKey', { key: dedupKey })}
          </Tag>
        )}
        {correlationId && (
          <Tag closable onClose={() => update({ correlationSystem: undefined, correlationId: undefined })}>
            {t('communications.byCorrelation', { system: correlationSystem ?? '', id: correlationId })}
          </Tag>
        )}
      </div>
      <CommunicationTable pages={pages} showWindow={executionId === undefined} />
    </Flex>
  );
}
