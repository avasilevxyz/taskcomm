import { ExportOutlined } from '@ant-design/icons';
import { Button, Tooltip } from 'antd';
import { lazy, useCallback } from 'react';
import { Link } from 'react-router';
import type { Level } from './api';
import { communicationPath, selectingText, shortId, taskPath } from './common';
import { useI18n } from './i18n';
import { useOpenPanel } from './panels';

// Карточка задачи или коммуникации в боковой панели стопки по щелчку на строке таблицы. Щелчок внутри
// карточки открывает следующую панель рядом; переход по ссылке уводит на страницу и закрывает панели.

const TaskView = lazy(() => import('./pages/TaskCard').then((module) => ({ default: module.TaskView })));
const CommunicationView = lazy(() =>
  import('./pages/CommunicationCard').then((module) => ({ default: module.CommunicationView })),
);

export type EntityTarget =
  | { kind: 'task'; level: Level; id: string; createdAt?: string }
  | { kind: 'communication'; id: string; createdAt?: string };

export function useEntityPanel(): (target: EntityTarget) => void {
  const { t } = useI18n();
  const openPanel = useOpenPanel();
  return useCallback(
    (target: EntityTarget) => {
      const page =
        target.kind === 'task'
          ? taskPath(target.level, target.id, target.createdAt)
          : communicationPath(target.id, target.createdAt);
      openPanel({
        kind: 'entity',
        label: `${t(target.kind === 'task' ? `level.${target.level}` : 'level.COMMUNICATION')} ${shortId(target.id)}`,
        extra: (
          <Tooltip title={t('panel.openPage')}>
            <Link to={page}>
              <Button type="text" icon={<ExportOutlined />} aria-label={t('panel.openPage')} />
            </Link>
          </Tooltip>
        ),
        render: () =>
          target.kind === 'task' ? (
            <TaskView level={target.level} id={target.id} createdAt={target.createdAt} />
          ) : (
            <CommunicationView id={target.id} createdAt={target.createdAt} />
          ),
      });
    },
    [openPanel, t],
  );
}

/** Сколько ждать второго щелчка: двойной выделяет слово, а не открывает карточку. */
export const DOUBLE_CLICK_WAIT = 250;

/** Открытие, ждущее, не будет ли второго щелчка; одно на всё приложение — щёлкают по одной строке. */
let pending: ReturnType<typeof setTimeout> | undefined;

/**
 * Свойства строки таблицы: щелчок открывает карточку в панели. Щелчок по ссылке или кнопке внутри строки
 * делает своё — панель не открывается; выделение текста — тоже. Двойной и тройной щелчок выделяют
 * слово и строку, а первый щелчок из них неотличим от одиночного, пока не пришёл второй, — поэтому
 * карточка открывается с короткой задержкой, а второй щелчок её отменяет.
 */
export function rowOpens(open: () => void) {
  return {
    onClick: (event: React.MouseEvent<HTMLElement>) => {
      clearTimeout(pending);
      pending = undefined;
      const row = event.currentTarget;
      if (event.detail > 1 || (event.target as HTMLElement).closest('a, button, .ant-table-row-expand-icon')) {
        return;
      }
      pending = setTimeout(() => {
        pending = undefined;
        if (!selectingText(row)) {
          open();
        }
      }, DOUBLE_CLICK_WAIT);
    },
    style: { cursor: 'pointer' },
  };
}
