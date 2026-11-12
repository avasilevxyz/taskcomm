import { useQuery } from '@tanstack/react-query';
import { Alert, Button, Flex, Input, Segmented, Select, Space, Tag, Typography } from 'antd';
import { useState } from 'react';
import { useSearchParams } from 'react-router';
import { ApiError, getJson, STATUSES, type Level, type SearchField } from '../api';
import { useMeta } from '../meta';
import { useWindowChoice, windowParams, WindowSelect } from '../common';
import { useI18n } from '../i18n';
import { TaskTable, useTaskPages } from './TaskTable';

const LEVELS: Level[] = ['TASK', 'BATCH', 'UNIT'];

export function Tasks() {
  const { t } = useI18n();
  const meta = useMeta();
  const [search, setSearch] = useSearchParams();
  const [choice, setChoice] = useWindowChoice();
  const levelParam = search.get('level');
  const level: Level = levelParam === 'BATCH' || levelParam === 'UNIT' ? levelParam : 'TASK';
  const type = search.get('type') ?? undefined;
  const statuses = search.getAll('status');
  const fieldName = search.get('field') ?? undefined;
  const fieldValue = search.get('value') ?? undefined;
  const executionId = search.get('executionId') ?? undefined;
  const executionCreatedAt = search.get('executionCreatedAt') ?? undefined;
  const [draft, setDraft] = useState({ name: fieldName, value: fieldValue ?? '' });

  const fields = useQuery({
    queryKey: ['tasks', 'search-fields'],
    queryFn: () => getJson<SearchField[]>('/tasks/search-fields'),
    refetchInterval: false,
  });
  const levelFields = (fields.data ?? []).filter((field) => field.level === level);
  const fieldOptions = [...new Map(levelFields.map((field) => [field.name, field])).values()].map((field) => ({
    value: field.name,
    label: field.title ? `${field.title} (${field.name})` : field.name,
  }));

  const pages = useTaskPages(
    [level, type, statuses, fieldName, fieldValue, executionId, executionCreatedAt, choice],
    () => ({
      level,
      type,
      status: statuses,
      executionId,
      executionCreatedAt,
      ...(fieldName && fieldValue ? { [`field.${fieldName}`]: fieldValue } : {}),
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

  const unknownField =
    pages.error instanceof ApiError && pages.error.body?.knownFields !== undefined ? pages.error.body : undefined;

  return (
    <Flex vertical gap={12}>
      <Typography.Title level={3}>{t('tasks.title')}</Typography.Title>
      <div className="toolbar">
        <Segmented<Level>
          value={level}
          options={LEVELS.map((value) => ({ value, label: t(`level.${value}`) }))}
          onChange={(value) => update({ level: value, status: [], field: undefined, value: undefined })}
        />
        <Select
          allowClear
          placeholder={t('common.type')}
          style={{ minWidth: 180 }}
          value={type}
          onChange={(value?: string) => update({ type: value })}
          options={(meta.data?.tasks?.types ?? []).map((item) => ({ value: item.type, label: item.type }))}
        />
        <Select
          mode="multiple"
          allowClear
          placeholder={t('common.status')}
          style={{ minWidth: 220 }}
          value={statuses}
          onChange={(value: string[]) => update({ status: value })}
          options={STATUSES[level].map((status) => ({ value: status, label: status }))}
        />
        <WindowSelect value={choice} onChange={setChoice} searchWindow={meta.data?.searchWindow} />
      </div>
      <div className="toolbar">
        <Space.Compact>
          <Select
            allowClear
            placeholder={t('tasks.field')}
            style={{ minWidth: 200 }}
            value={draft.name}
            onChange={(name?: string) => setDraft({ ...draft, name })}
            options={fieldOptions}
          />
          <Input
            placeholder={t('tasks.fieldValue')}
            style={{ width: 220 }}
            value={draft.value}
            onChange={(event) => setDraft({ ...draft, value: event.target.value })}
            onPressEnter={() => update({ field: draft.name, value: draft.value })}
          />
          <Button type="primary" onClick={() => update({ field: draft.name, value: draft.value })}>
            OK
          </Button>
        </Space.Compact>
        {executionId && (
          <Tag closable onClose={() => update({ executionId: undefined, executionCreatedAt: undefined })}>
            {t('tasks.inExecution', { id: executionId })}
          </Tag>
        )}
      </div>
      {unknownField ? (
        <Alert type="warning" showIcon title={t('tasks.unknownField', { fields: unknownField.knownFields?.join(', ') ?? '' })} />
      ) : (
        <TaskTable pages={pages} showWindow={executionId === undefined} />
      )}
    </Flex>
  );
}
