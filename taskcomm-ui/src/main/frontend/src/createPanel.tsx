import { Button } from 'antd';
import { lazy, useCallback, useMemo, type MouseEvent, type ReactNode } from 'react';
import { Link, useHref } from 'react-router';
import { useI18n } from './i18n';
import { useOpenPanel } from './panels';
import type { CreatePrefill } from './pages/Create';

// Форма создания в боковой панели стопки (docs/adr/0153-ui-creation-refinements.md): рядом с карточкой,
// из которой её открыли, а не под ней. Обычный щелчок открывает панель; ссылка, открытая в новой вкладке
// или окне, ведёт на страницу /create.

const CreatePanel = lazy(() => import('./pages/Create').then((module) => ({ default: module.CreatePanel })));

type Open = (prefill?: CreatePrefill) => void;

export function useCreatePanel(): Open {
  const { t } = useI18n();
  const openPanel = useOpenPanel();
  return useCallback<Open>(
    (prefill = {}) =>
      openPanel({
        kind: 'create',
        label: t('create.title'),
        render: (close) => <CreatePanel prefill={prefill} onDone={close} />,
      }),
    [openPanel, t],
  );
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
  const open = useCreatePanel();
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
  const open = useCreatePanel();
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
