import { Button, Drawer, Spin } from 'antd';
import { createContext, lazy, Suspense, useCallback, useContext, useMemo, useState, type MouseEvent, type ReactNode } from 'react';
import { Link, useHref } from 'react-router';
import { useDrawerSize } from './drawerSize';
import { useI18n } from './i18n';
import type { CreatePrefill } from './pages/Create';

// Форма создания в боковой панели поверх текущего экрана (docs/adr/0153-ui-creation-refinements.md).
// Обычный щелчок открывает панель; ссылка, открытая в новой вкладке или окне, ведёт на страницу /create.

const CreatePanel = lazy(() => import('./pages/Create').then((module) => ({ default: module.CreatePanel })));

type Open = (prefill?: CreatePrefill) => void;

const CreateDrawerContext = createContext<Open>(() => undefined);

export function CreateDrawerProvider({ children }: { children: ReactNode }) {
  const { t } = useI18n();
  const [state, setState] = useState<{ open: boolean; prefill: CreatePrefill }>({ open: false, prefill: {} });
  const open = useCallback<Open>((prefill = {}) => setState({ open: true, prefill }), []);
  const close = useCallback(() => setState((current) => ({ ...current, open: false })), []);
  const size = useDrawerSize('create');
  return (
    <CreateDrawerContext.Provider value={open}>
      {children}
      <Drawer open={state.open} onClose={close} title={t('create.title')} {...size}>
        <Suspense fallback={<Spin />}>
          <CreatePanel prefill={state.prefill} onDone={close} />
        </Suspense>
      </Drawer>
    </CreateDrawerContext.Provider>
  );
}

export function useCreateDrawer(): Open {
  return useContext(CreateDrawerContext);
}

/** Обычный щелчок — в своей вкладке, без модификаторов: его перехватывает панель. */
function plainClick(event: MouseEvent): boolean {
  return event.button === 0 && !event.metaKey && !event.ctrlKey && !event.shiftKey && !event.altKey;
}

function createPath(prefill: CreatePrefill): string {
  const search = new URLSearchParams();
  if (prefill.executionId) {
    search.set('executionId', prefill.executionId);
  }
  if (prefill.executionCreatedAt) {
    search.set('executionCreatedAt', prefill.executionCreatedAt);
  }
  const text = search.toString();
  return text === '' ? '/create' : `/create?${text}`;
}

/** Ссылка «Создать» для меню. */
export function CreateLink({ children }: { children: ReactNode }) {
  const open = useCreateDrawer();
  return (
    <Link
      to="/create"
      onClick={(event) => {
        if (plainClick(event)) {
          event.preventDefault();
          open();
        }
      }}
    >
      {children}
    </Link>
  );
}

/** Кнопка «Создать в этом деле» — с уже известным делом. */
export function CreateInExecutionButton({ executionId, executionCreatedAt }: Required<CreatePrefill>) {
  const { t } = useI18n();
  const open = useCreateDrawer();
  const prefill = useMemo(() => ({ executionId, executionCreatedAt }), [executionId, executionCreatedAt]);
  const href = useHref(createPath(prefill));
  return (
    <Button
      href={href}
      onClick={(event) => {
        if (plainClick(event)) {
          event.preventDefault();
          open(prefill);
        }
      }}
    >
      {t('create.here')}
    </Button>
  );
}
