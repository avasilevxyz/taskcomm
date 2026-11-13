import { ExportOutlined } from '@ant-design/icons';
import { Button, Drawer, Spin, Tooltip } from 'antd';
import { createContext, lazy, Suspense, useCallback, useContext, useEffect, useState, type ReactNode } from 'react';
import { Link, useLocation } from 'react-router';
import type { Level } from './api';
import { communicationPath, taskPath } from './common';
import { useDrawerSize } from './drawerSize';
import { useI18n } from './i18n';

// Карточка задачи или коммуникации в боковой панели по щелчку на строке таблицы. Переход по ссылке
// внутри карточки уводит на страницу — панель при этом закрывается.

const TaskView = lazy(() => import('./pages/TaskCard').then((module) => ({ default: module.TaskView })));
const CommunicationView = lazy(() =>
  import('./pages/CommunicationCard').then((module) => ({ default: module.CommunicationView })),
);

export type EntityTarget =
  | { kind: 'task'; level: Level; id: string; createdAt?: string }
  | { kind: 'communication'; id: string; createdAt?: string };

const EntityDrawerContext = createContext<(target: EntityTarget) => void>(() => undefined);

export function EntityDrawerProvider({ children }: { children: ReactNode }) {
  const { t } = useI18n();
  const location = useLocation();
  // Карточка остаётся в панели и во время анимации закрытия — иначе панель уезжала бы пустой.
  const [state, setState] = useState<{ open: boolean; target: EntityTarget | null }>({ open: false, target: null });
  const target = state.target;
  const open = useCallback((next: EntityTarget) => setState({ open: true, target: next }), []);
  const close = useCallback(() => setState((current) => ({ ...current, open: false })), []);
  const size = useDrawerSize('entity');

  useEffect(() => {
    close();
  }, [location.pathname, location.search, close]);

  const page = target
    ? target.kind === 'task'
      ? taskPath(target.level, target.id, target.createdAt)
      : communicationPath(target.id, target.createdAt)
    : undefined;

  return (
    <EntityDrawerContext.Provider value={open}>
      {children}
      <Drawer
        open={state.open}
        onClose={close}
        {...size}
        destroyOnHidden
        extra={
          page && (
            <Tooltip title={t('drawer.openPage')}>
              <Link to={page}>
                <Button icon={<ExportOutlined />} />
              </Link>
            </Tooltip>
          )
        }
      >
        <Suspense fallback={<Spin />}>
          {target?.kind === 'task' && <TaskView level={target.level} id={target.id} createdAt={target.createdAt} />}
          {target?.kind === 'communication' && <CommunicationView id={target.id} createdAt={target.createdAt} />}
        </Suspense>
      </Drawer>
    </EntityDrawerContext.Provider>
  );
}

export function useEntityDrawer(): (target: EntityTarget) => void {
  return useContext(EntityDrawerContext);
}

/**
 * Свойства строки таблицы: щелчок открывает карточку в панели. Щелчок по ссылке или кнопке внутри строки
 * делает своё — панель не открывается.
 */
export function rowOpens(open: () => void) {
  return {
    onClick: (event: React.MouseEvent) => {
      if ((event.target as HTMLElement).closest('a, button, .ant-table-row-expand-icon')) {
        return;
      }
      open();
    },
    style: { cursor: 'pointer' },
  };
}
