import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Alert, Button, Checkbox, Modal, Popconfirm, Table, Tag, Tooltip, Typography } from 'antd';
import { useState } from 'react';
import { ApiError, postJson, type ActionResult, type CommunicationItem, type Level } from './api';
import { shortId } from './common';
import { useI18n } from './i18n';

// Перезапуск нескольких сущностей сразу (docs/adr/0169-restart-many.md): задачи, группы и подзадачи — одним
// запросом, одной транзакцией на сервере, чтобы родитель не ушёл в сборку между перезапусками; коммуникации —
// каждая своим запросом: сборки у них нет, и гонки тоже.

export type RestartKind = Level | 'COMMUNICATION';

export interface RestartTarget {
  kind: RestartKind;
  id: string;
  executionCreatedAt: string;
}

export interface RestartOutcome {
  target: RestartTarget;
  accepted: boolean;
  reason?: string;
}

/** Сколько задач сервер перезапускает за один запрос — TaskService.RESTART_ALL_LIMIT. */
const TASK_CHUNK = 500;

const PARENT_RESTARTABLE = new Set(['SUCCEEDED', 'WARNING', 'FAILED', 'SKIPPED']);

/** Правила сервера: подзадача — только упавшая, группа и задача — завершённые; остальное решит сервер. */
export function taskRestartable(level: Level, status: string): boolean {
  return level === 'UNIT' ? status === 'FAILED' : PARENT_RESTARTABLE.has(status);
}

/** Коммуникация — только упавшая и не синхронная исходящая (docs/adr/0167-communication-run-mode.md). */
export function communicationRestartable(item: CommunicationItem): boolean {
  return item.state.status === 'FAILED' && !(item.direction === 'OUTBOUND' && item.runMode === 'SYNC');
}

async function restartAll(targets: RestartTarget[]): Promise<RestartOutcome[]> {
  const tasks = targets.filter((target) => target.kind !== 'COMMUNICATION');
  const communications = targets.filter((target) => target.kind === 'COMMUNICATION');
  const outcomes: RestartOutcome[] = [];
  for (let start = 0; start < tasks.length; start += TASK_CHUNK) {
    const chunk = tasks.slice(start, start + TASK_CHUNK);
    const response = await postJson<{ level: Level; id: string; accepted: boolean; reason: string }[]>(
      '/tasks/restart',
      {},
      JSON.stringify(chunk.map((target) => ({ level: target.kind, id: target.id, executionCreatedAt: target.executionCreatedAt }))),
    );
    response.body.forEach((item, index) => outcomes.push({ target: chunk[index], accepted: item.accepted, reason: item.reason }));
  }
  const replies = await Promise.all(
    communications.map((target) =>
      postJson<ActionResult>(`/communications/${target.id}/restart`, { executionCreatedAt: target.executionCreatedAt })
        .then((response) => ({ target, accepted: response.body.accepted, reason: response.body.reason }))
        .catch((error: unknown) => ({ target, accepted: false, reason: error instanceof Error ? error.message : String(error) })),
    ),
  );
  return [...outcomes, ...replies];
}

/**
 * Флажок выбора для перезапуска. Недоступный — с подсказкой, почему: по наведению и по щелчку.
 * Щелчок по флажку строку не открывает.
 */
export function RestartBox({ checked, disabled, hint, onChange }: {
  checked: boolean;
  disabled: boolean;
  hint?: string;
  onChange: (checked: boolean) => void;
}) {
  const box = (
    <span data-no-open onClick={(event) => event.stopPropagation()}>
      <Checkbox checked={checked} disabled={disabled} onChange={(event) => onChange(event.target.checked)} />
    </span>
  );
  return hint ? (
    <Tooltip title={hint} trigger={['hover', 'click']}>
      {box}
    </Tooltip>
  ) : (
    box
  );
}

/** Кнопка «Перезапустить выбранные» и исход по каждой сущности в окне. */
export function RestartSelected({ targets, onDone }: { targets: RestartTarget[]; onDone: () => void }) {
  const { t } = useI18n();
  const client = useQueryClient();
  const [outcomes, setOutcomes] = useState<RestartOutcome[] | null>(null);
  const [failure, setFailure] = useState<string | null>(null);
  const action = useMutation({
    mutationFn: () => restartAll(targets),
    onSuccess: (result) => {
      setOutcomes(result);
      onDone();
      void client.invalidateQueries();
    },
    onError: (error) =>
      setFailure(error instanceof ApiError && error.forbidden ? t('common.forbidden') : error.message),
  });
  const accepted = outcomes?.filter((outcome) => outcome.accepted).length ?? 0;
  return (
    <>
      <Popconfirm
        title={t('restart.confirm', { count: targets.length })}
        onConfirm={() => {
          setFailure(null);
          action.mutate();
        }}
        disabled={targets.length === 0}
      >
        <Button size="small" disabled={targets.length === 0} loading={action.isPending}>
          {t('restart.selected', { count: targets.length })}
        </Button>
      </Popconfirm>
      {failure && <Alert type="error" showIcon closable title={failure} onClose={() => setFailure(null)} />}
      <Modal
        open={outcomes !== null}
        title={t('restart.result', { accepted, total: outcomes?.length ?? 0 })}
        onCancel={() => setOutcomes(null)}
        footer={null}
        width={720}
      >
        <Table<RestartOutcome>
          size="small"
          pagination={false}
          rowKey={(outcome) => `${outcome.target.kind}:${outcome.target.id}`}
          dataSource={outcomes ?? []}
          columns={[
            { title: t('common.type'), render: (_, outcome) => t(`level.${outcome.target.kind}`) },
            { title: t('common.id'), render: (_, outcome) => <Typography.Text code>{shortId(outcome.target.id)}</Typography.Text> },
            {
              title: t('common.status'),
              render: (_, outcome) => (
                <Tag color={outcome.accepted ? 'success' : 'warning'}>
                  {t(outcome.accepted ? 'common.accepted' : 'restart.rejected')}
                </Tag>
              ),
            },
            { title: t('common.reason'), render: (_, outcome) => outcome.reason },
          ]}
        />
      </Modal>
    </>
  );
}
