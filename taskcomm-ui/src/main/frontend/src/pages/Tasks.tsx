import { Alert, Button, Flex, Input, Segmented, Select, Space, Tag, Tooltip, Typography } from 'antd';
import { useState } from 'react';
import { useSearchParams } from 'react-router';
import { ApiError, STATUSES, type Level } from '../api';
import { useMeta } from '../meta';
import { NO_AUTOFILL, processorOption, useProcessorNames, useStatusOptions } from '../common';
import { useWindowChoice, windowParams } from '../windowChoice';
import { useI18n } from '../i18n';
import { fieldGroups, useSearchFields, type FieldOption } from './searchFields';
import { TaskTable, useTaskPages } from './TaskTable';

const LEVELS: Level[] = ['TASK', 'BATCH', 'UNIT'];

export function Tasks() {
  const { t } = useI18n();
  const meta = useMeta();
  const statusOptions = useStatusOptions();
  const names = useProcessorNames();
  const [search, setSearch] = useSearchParams();
  const [choice] = useWindowChoice();
  const levelParam = search.get('level');
  const level: Level = levelParam === 'BATCH' || levelParam === 'UNIT' ? levelParam : 'TASK';
  const type = search.get('type') ?? undefined;
  const statuses = search.getAll('status');
  const fieldName = search.get('field') ?? undefined;
  const fieldValue = search.get('value') ?? undefined;
  const executionId = search.get('executionId') ?? undefined;
  const executionCreatedAt = search.get('executionCreatedAt') ?? undefined;
  const [draft, setDraft] = useState({ name: fieldName, value: fieldValue ?? '' });

  const fields = useSearchFields();
  const groups = fieldGroups(fields.data ?? [], level, type);
  const fieldOptions: (FieldOption | { label: string; title: string; options: FieldOption[] })[] =
    type !== undefined
      ? (groups[0]?.options ?? [])
      : groups.map((group) => ({
          label: group.taskType === undefined ? t('tasks.sharedFields') : names('task', group.taskType).title,
          title: group.taskType ?? '',
          options: group.options,
        }));
  // Поле, которого у нового типа нет, сбрасывается: поиск по нему в этом типе ничего не найдёт.
  const changeType = (next?: string) => {
    const known = new Set(fieldGroups(fields.data ?? [], level, next).flatMap((group) => group.options.map((option) => option.value)));
    if (fieldName !== undefined && !known.has(fieldName)) {
      setDraft({ name: undefined, value: '' });
      update({ type: next, field: undefined, value: undefined });
    } else {
      if (draft.name !== undefined && !known.has(draft.name)) {
        setDraft({ ...draft, name: undefined });
      }
      update({ type: next });
    }
  };

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
        <Tooltip title={t('common.type')}>
          <Select
            allowClear
            placeholder={t('common.type')}
            style={{ minWidth: 180 }}
            value={type}
            onChange={changeType}
            showSearch={{ optionFilterProp: 'label' }}
            options={(meta.data?.tasks?.types ?? []).map((item) => processorOption({ id: item.type, title: item.title }))}
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
            options={statusOptions(STATUSES[level])}
          />
        </Tooltip>
      </div>
      <div className="toolbar">
        <Space.Compact style={{ maxWidth: '100%' }}>
          <Tooltip title={t('tasks.field')}>
            <Select
              allowClear
              placeholder={t('tasks.field')}
              style={{ width: 200, minWidth: 0, flexShrink: 1 }}
              popupMatchSelectWidth={false}
              showSearch={{ optionFilterProp: 'label' }}
              value={draft.name}
              onChange={(name?: string) => setDraft({ ...draft, name })}
              options={fieldOptions}
              optionRender={(option) => (
                <Flex vertical>
                  <span>{option.label}</span>
                  {'description' in option.data && option.data.description && (
                    <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                      {option.data.description}
                    </Typography.Text>
                  )}
                </Flex>
              )}
            />
          </Tooltip>
          <Input
            autoComplete={NO_AUTOFILL}
            placeholder={t('tasks.fieldValue')}
            style={{ width: 220, minWidth: 0, flexShrink: 1 }}
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
        <TaskTable pages={pages} showWindow={executionId === undefined} level={level} taskType={type} />
      )}
    </Flex>
  );
}
