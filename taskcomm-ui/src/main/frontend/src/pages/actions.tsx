import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Alert, Button, Popconfirm, Space } from 'antd';
import { useState, type ReactNode } from 'react';
import { ApiError, postJson, type ActionResult, type Params } from '../api';
import { useI18n } from '../i18n';

/**
 * Отмена и перезапуск: отказ (409) — исход с причиной, а не ошибка; после действия затронутые
 * экраны перечитываются сразу, не дожидаясь опроса.
 */
export function Actions({ path, params, cancellable, restartable, extra }: {
  path: string;
  params: Params;
  cancellable: boolean;
  restartable: boolean;
  /** Прочие действия карточки — в той же строке. */
  extra?: ReactNode;
}) {
  const { t } = useI18n();
  const client = useQueryClient();
  const [outcome, setOutcome] = useState<{ ok: boolean; text: string } | null>(null);
  const action = useMutation({
    mutationFn: (name: 'cancel' | 'restart') => postJson<ActionResult>(`${path}/${name}`, params),
    onSuccess: (response) => {
      setOutcome(
        response.body.accepted
          ? { ok: true, text: t('common.accepted') }
          : { ok: false, text: t('common.rejected', { reason: response.body.reason ?? '' }) },
      );
      void client.invalidateQueries();
    },
    onError: (error) => {
      setOutcome({
        ok: false,
        text: error instanceof ApiError && error.forbidden ? t('common.forbidden') : error.message,
      });
    },
  });
  return (
    <Space orientation="vertical">
      <Space wrap>
        <Popconfirm title={t('common.confirm')} onConfirm={() => action.mutate('cancel')} disabled={!cancellable}>
          <Button danger disabled={!cancellable} loading={action.isPending && action.variables === 'cancel'}>
            {t('common.cancel')}
          </Button>
        </Popconfirm>
        <Popconfirm title={t('common.confirm')} onConfirm={() => action.mutate('restart')} disabled={!restartable}>
          <Button disabled={!restartable} loading={action.isPending && action.variables === 'restart'}>
            {t('common.restart')}
          </Button>
        </Popconfirm>
        {extra}
      </Space>
      {outcome && <Alert type={outcome.ok ? 'success' : 'warning'} showIcon closable title={outcome.text} />}
    </Space>
  );
}

const FINAL = new Set(['SUCCEEDED', 'WARNING', 'FAILED', 'CANCELLED', 'SKIPPED', 'ABANDONED']);

/** Отменить можно незавершённое; перезапустить — завершённое неуспешно. Остальное решит сервер. */
export function canCancel(status: string): boolean {
  return !FINAL.has(status);
}

export function canRestart(status: string): boolean {
  return status === 'FAILED' || status === 'WARNING';
}
