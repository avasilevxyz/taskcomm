import { ReloadOutlined, SyncOutlined } from '@ant-design/icons';
import { useIsFetching, useQueryClient } from '@tanstack/react-query';
import { Button, Select, Space, Tooltip } from 'antd';
import { useEffect, useRef, useState } from 'react';
import { useI18n } from './i18n';

// Опрос открытого экрана (docs/adr/0152-web-interface-frontend-stack.md): период выбирается в шапке
// и помнится в браузере. Ноль — опрос выключен, и загрузки при возврате на вкладку тоже нет:
// данные обновляются только по кнопке.

const KEY = 'taskcomm.refresh';

/** Варианты периода, мс. */
export const REFRESH_CHOICES = [0, 5_000, 10_000, 30_000, 60_000] as const;

/** Период по умолчанию, мс. */
export const DEFAULT_REFRESH = 10_000;

function load(): number {
  try {
    const value = Number(window.localStorage.getItem(KEY));
    return (REFRESH_CHOICES as readonly number[]).includes(value) && window.localStorage.getItem(KEY) !== null
      ? value
      : DEFAULT_REFRESH;
  } catch {
    return DEFAULT_REFRESH;
  }
}

let current = load();

/**
 * Период опроса для {@code refetchInterval} по умолчанию. Функция, а не число: запрос спрашивает её
 * после каждой загрузки, поэтому новый выбор действует без пересоздания запросов.
 */
export function refreshInterval(): number | false {
  return current > 0 ? current : false;
}

/** Сколько иконка крутится как минимум, мс: быстрый ответ иначе виден только миганием. */
const MIN_SPIN = 500;

/** Признак, который, став истинным, держится не меньше {@code minimum} мс. */
function useAtLeast(flag: boolean, minimum: number): boolean {
  const [shown, setShown] = useState(flag);
  const since = useRef(0);
  useEffect(() => {
    if (flag) {
      since.current = Date.now();
      setShown(true);
      return undefined;
    }
    const timer = window.setTimeout(() => setShown(false), Math.max(0, since.current + minimum - Date.now()));
    return () => window.clearTimeout(timer);
  }, [flag, minimum]);
  return flag || shown;
}

/** Выбор периода и «обновить сейчас» — в шапке. */
export function RefreshControl() {
  const { t } = useI18n();
  const client = useQueryClient();
  const [value, setValue] = useState(current);
  // Крутится, пока к серверу идёт хоть один запрос, а не всё время, пока включён опрос.
  const fetching = useAtLeast(useIsFetching() > 0, MIN_SPIN);
  const refetch = () => void client.refetchQueries({ type: 'active' });
  return (
    <Space size={4} className="refresh">
      <Tooltip title={t('refresh.hint')} placement="bottomRight">
        <Select
          size="small"
          value={value}
          prefix={<SyncOutlined spin={fetching} />}
          popupMatchSelectWidth={false}
          aria-label={t('refresh.hint')}
          onChange={(next: number) => {
            current = next;
            setValue(next);
            try {
              window.localStorage.setItem(KEY, String(next));
            } catch {
              // не запомнили — выбор действует до перезагрузки
            }
            // Загрузка заново пересчитывает таймеры запросов уже с новым периодом.
            refetch();
          }}
          options={REFRESH_CHOICES.map((choice) => ({
            value: choice,
            label: choice === 0 ? t('refresh.off') : t('refresh.every', { seconds: choice / 1000 }),
          }))}
        />
      </Tooltip>
      <Tooltip title={t('refresh.now')} placement="bottomRight">
        <Button size="small" type="text" icon={<ReloadOutlined />} aria-label={t('refresh.now')} onClick={refetch} />
      </Tooltip>
    </Space>
  );
}
