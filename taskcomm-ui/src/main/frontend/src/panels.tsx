import { CloseOutlined, VerticalLeftOutlined } from '@ant-design/icons';
import { Button, Spin, theme, Tooltip } from 'antd';
import {
  createContext,
  Fragment,
  Suspense,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type PointerEvent as ReactPointerEvent,
  type ReactNode,
} from 'react';
import { createPortal } from 'react-dom';
import { useLocation } from 'react-router';
import { useI18n } from './i18n';
import { expanded, MIN_PANEL_WIDTH, shownWidth, toggledWidth } from './panelLayout';

// Стопка боковых панелей: карточки и форма создания открываются рядом, а не друг поверх друга.
// Новая встаёт у правого края, прежние сдвигаются влево; не помещаются — самые старые сворачиваются
// в вертикальные полосы с названием, и щелчок по полосе раскрывает её, сворачивая самые новые справа.
// Открытая из панели панель заменяет все, что правее неё: цепочка читается как путь.
// Страница под панелями размыта; щелчок по ней закрывает все, Esc — правую.

/** Вид панели — у каждого своя запомненная ширина. */
export type PanelKind = 'entity' | 'create' | 'columns' | 'code';

/** Что открыть. */
export interface PanelSpec {
  kind: PanelKind;
  /** Название — в шапке и на свёрнутой полосе. */
  label: string;
  /** Кнопки в шапке рядом с закрытием. */
  extra?: ReactNode;
  /** Содержимое; {@code close} закрывает эту панель и те, что правее. */
  render: (close: () => void) => ReactNode;
}

interface Opened {
  id: number;
  spec: PanelSpec;
  /** Свёрнута кнопкой — полоса, сколько бы места ни было, пока по ней не щёлкнут. */
  collapsed?: boolean;
  /** Название, которое поставило содержимое, когда загрузилось, — вместо начального. */
  label?: string;
}

interface Stack {
  open: (spec: PanelSpec, after?: number) => void;
  label: (index: number, label: string) => void;
}

const StackContext = createContext<Stack>({ open: () => undefined, label: () => undefined });

/** Номер панели, внутри которой находится компонент; на странице — нет. */
const IndexContext = createContext<number | undefined>(undefined);

/** Место для кнопок содержимого в шапке панели, слева от «Свернуть»; на странице — нет. */
const ActionsContext = createContext<HTMLElement | null>(null);

const STORAGE_PREFIX = 'taskcomm.panel.';

/** Ширина, с которой панель начинается: карточке и форме — «large» у antd, настройкам — узкая. */
const DEFAULT_WIDTH: Record<PanelKind, number> = { entity: 736, create: 736, columns: 400, code: 736 };

function loadWidth(kind: PanelKind): number {
  try {
    const stored = Number(window.localStorage.getItem(STORAGE_PREFIX + kind));
    return stored > 0 ? stored : DEFAULT_WIDTH[kind];
  } catch {
    return DEFAULT_WIDTH[kind];
  }
}

function saveWidth(kind: PanelKind, width: number): void {
  try {
    window.localStorage.setItem(STORAGE_PREFIX + kind, String(Math.round(width)));
  } catch {
    // не запомнили — ширина действует до перезагрузки
  }
}

/** Открыть панель: со страницы — новую цепочку, из панели — следом за ней. */
export function useOpenPanel(): (spec: PanelSpec) => void {
  const stack = useContext(StackContext);
  const index = useContext(IndexContext);
  return useCallback((spec: PanelSpec) => stack.open(spec, index), [stack, index]);
}

/** Внутри ли боковой панели: карточка там не повторяет заголовок, который уже в шапке панели. */
export function useInPanel(): boolean {
  return useContext(IndexContext) !== undefined;
}

/**
 * Название панели, в которой находится компонент, — в шапке и на свёрнутой полосе. Ставит его
 * содержимое: при открытии известен только вид сущности, а название — когда она загрузилась.
 */
export function usePanelLabel(label: string | undefined): void {
  const stack = useContext(StackContext);
  const index = useContext(IndexContext);
  useEffect(() => {
    if (index !== undefined && label) {
      stack.label(index, label);
    }
  }, [stack, index, label]);
}

/**
 * Кнопки содержимого в шапке панели, рядом со «Свернуть»: они видны, как бы ни было прокручено
 * содержимое. Вне панели не рисуется ничего — там кнопки остаются в самом содержимом.
 */
export function PanelActions({ children }: { children: ReactNode }) {
  const node = useContext(ActionsContext);
  return node ? createPortal(children, node) : null;
}

function useWindowWidth(): number {
  const [width, setWidth] = useState(() => window.innerWidth);
  useEffect(() => {
    const update = () => setWidth(window.innerWidth);
    window.addEventListener('resize', update);
    return () => window.removeEventListener('resize', update);
  }, []);
  return width;
}

/** Поля ввода, списки и редактор сами отвечают на Esc — панель тогда не закрывается. */
function ownsEscape(target: EventTarget | null): boolean {
  return target instanceof HTMLElement && target.closest('input, textarea, [role="combobox"], .cm-editor') !== null;
}

export function PanelStackProvider({ children }: { children: ReactNode }) {
  const [panels, setPanels] = useState<Opened[]>([]);
  const [focus, setFocus] = useState(0);
  const [widths, setWidths] = useState<Record<PanelKind, number>>(() => ({
    entity: loadWidth('entity'),
    create: loadWidth('create'),
    columns: loadWidth('columns'),
    code: loadWidth('code'),
  }));
  const counter = useRef(0);
  const location = useLocation();
  const available = useWindowWidth();

  const open = useCallback((spec: PanelSpec, after?: number) => {
    counter.current += 1;
    const opened = { id: counter.current, spec };
    setPanels((current) => {
      const next = [...current.slice(0, after === undefined ? 0 : after + 1), opened];
      setFocus(next.length - 1);
      return next;
    });
  }, []);
  const closeFrom = useCallback((index: number) => {
    setPanels((current) => current.slice(0, index));
    setFocus(Math.max(0, index - 1));
  }, []);
  const label = useCallback((index: number, text: string) => {
    setPanels((current) =>
      current[index] && current[index].label !== text
        ? current.map((panel, at) => (at === index ? { ...panel, label: text } : panel))
        : current,
    );
  }, []);
  const stack = useMemo(() => ({ open, label }), [open, label]);
  // Свернуть вручную; выбранной становится самая новая из раскрытых.
  const collapse = useCallback((index: number) => {
    setPanels((current) => {
      const next = current.map((panel, at) => (at === index ? { ...panel, collapsed: true } : panel));
      const newest = next.map((panel, at) => (panel.collapsed ? -1 : at)).filter((at) => at >= 0).pop();
      setFocus((selected) => (selected === index ? (newest ?? index) : selected));
      return next;
    });
  }, []);
  // Щелчок по полосе раскрывает её, даже свёрнутую вручную, и делает выбранной.
  const expand = useCallback((index: number) => {
    setPanels((current) => current.map((panel, at) => (at === index ? { ...panel, collapsed: false } : panel)));
    setFocus(index);
  }, []);

  // Переход на другую страницу закрывает все панели: их содержимое было про прежний экран.
  useEffect(() => {
    setPanels([]);
  }, [location.pathname, location.search]);

  useEffect(() => {
    if (panels.length === 0) {
      return undefined;
    }
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && !event.defaultPrevented && !ownsEscape(event.target)) {
        closeFrom(panels.length - 1);
      }
    };
    window.addEventListener('keydown', onKey);
    const overflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      window.removeEventListener('keydown', onKey);
      document.body.style.overflow = overflow;
    };
  }, [panels.length, closeFrom]);

  const resize = (kind: PanelKind, width: number) => {
    const next = Math.max(MIN_PANEL_WIDTH, Math.min(width, available));
    setWidths((current) => ({ ...current, [kind]: next }));
    saveWidth(kind, next);
  };

  return (
    <StackContext.Provider value={stack}>
      {children}
      {panels.length > 0 &&
        createPortal(
          <PanelStack
            panels={panels}
            focus={focus}
            widths={panels.map((panel) => widths[panel.spec.kind])}
            available={available}
            onFocus={expand}
            onCollapse={collapse}
            onClose={closeFrom}
            onResize={(index, width) => resize(panels[index].spec.kind, width)}
          />,
          document.body,
        )}
    </StackContext.Provider>
  );
}

function PanelStack({ panels, focus, widths, available, onFocus, onCollapse, onClose, onResize }: {
  panels: Opened[];
  focus: number;
  widths: number[];
  available: number;
  onFocus: (index: number) => void;
  onCollapse: (index: number) => void;
  onClose: (index: number) => void;
  onResize: (index: number, width: number) => void;
}) {
  const { t } = useI18n();
  const { token } = theme.useToken();
  // Узлы мест для кнопок в шапках, по панели; ссылка на каждый — одна, иначе React звал бы её при
  // каждой перерисовке и состояние менялось бы по кругу.
  const [slots, setSlots] = useState<Record<number, HTMLElement | null>>({});
  const slotRefs = useRef(new Map<number, (node: HTMLElement | null) => void>());
  const slotRef = (id: number) => {
    let ref = slotRefs.current.get(id);
    if (!ref) {
      ref = (node: HTMLElement | null) =>
        setSlots((current) => (current[id] === node ? current : { ...current, [id]: node }));
      slotRefs.current.set(id, ref);
    }
    return ref;
  };
  const open = expanded(
    widths,
    focus,
    available,
    panels.map((panel) => panel.collapsed === true),
  );
  const surface = {
    background: token.colorBgElevated,
    color: token.colorText,
    borderLeft: `1px solid ${token.colorBorderSecondary}`,
  };
  // Стопка — в портале на body, вне контейнера, которому Ant Design задаёт шрифт: свои шапки и полосы
  // иначе получили бы шрифт браузера по умолчанию.
  const typography = { fontFamily: token.fontFamily, fontSize: token.fontSize, lineHeight: token.lineHeight };

  const startResize = (index: number, event: ReactPointerEvent<HTMLDivElement>) => {
    event.preventDefault();
    const handle = event.currentTarget;
    handle.setPointerCapture(event.pointerId);
    const startX = event.clientX;
    const startWidth = shownWidth(widths, open, index, available);
    const move = (moved: PointerEvent) => onResize(index, startWidth + startX - moved.clientX);
    const stop = () => {
      handle.removeEventListener('pointermove', move);
      handle.removeEventListener('pointerup', stop);
    };
    handle.addEventListener('pointermove', move);
    handle.addEventListener('pointerup', stop);
  };

  return (
    <>
      <div className="panels-backdrop" onClick={() => onClose(0)} />
      <div className="panels" style={typography}>
        {/* Свёрнутая панель не размонтируется: её содержимое уходит за край экрана, а на её месте —
            полоса. Так при раскрытии сохраняются прокрутка, выбранная попытка, вкладки — всё как было. */}
        {panels.map((panel, index) => (
          <Fragment key={panel.id}>
            <section
              className={open[index] ? 'panel' : 'panel panel-stowed'}
              role="dialog"
              aria-label={panel.label ?? panel.spec.label}
              aria-hidden={!open[index]}
              inert={!open[index]}
              style={{
                ...surface,
                width: open[index] ? shownWidth(widths, open, index, available) : Math.min(widths[index], available),
              }}
            >
              <div
                className="panel-resize"
                role="separator"
                aria-orientation="vertical"
                aria-label={t('panel.resize')}
                onPointerDown={(event) => startResize(index, event)}
                // Двойной щелчок: шире минимума — до минимума, уже минимальная — до доли окна.
                onDoubleClick={() => onResize(index, toggledWidth(shownWidth(widths, open, index, available), available))}
              />
              <header className="panel-header" style={{ borderBottom: `1px solid ${token.colorBorderSecondary}` }}>
                <span className="panel-title">{panel.label ?? panel.spec.label}</span>
                {panel.spec.extra}
                <span className="panel-actions" ref={slotRef(panel.id)} />
                <Tooltip title={t('panel.collapse')}>
                  <Button
                    type="text"
                    icon={<VerticalLeftOutlined />}
                    aria-label={t('panel.collapse')}
                    onClick={() => onCollapse(index)}
                  />
                </Tooltip>
                <Tooltip title={t('panel.close')}>
                  <Button type="text" icon={<CloseOutlined />} aria-label={t('panel.close')} onClick={() => onClose(index)} />
                </Tooltip>
              </header>
              <div className="panel-body">
                <IndexContext.Provider value={index}>
                  <ActionsContext.Provider value={slots[panel.id] ?? null}>
                    <Suspense fallback={<Spin />}>{panel.spec.render(() => onClose(index))}</Suspense>
                  </ActionsContext.Provider>
                </IndexContext.Provider>
              </div>
            </section>
            {!open[index] && (
              <button
                type="button"
                className="panel-strip"
                style={surface}
                title={panel.label ?? panel.spec.label}
                aria-label={t('panel.expand', { title: panel.label ?? panel.spec.label })}
                onClick={() => onFocus(index)}
              >
                <span>{panel.label ?? panel.spec.label}</span>
              </button>
            )}
          </Fragment>
        ))}
      </div>
    </>
  );
}
