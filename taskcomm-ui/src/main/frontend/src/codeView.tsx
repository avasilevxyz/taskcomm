import { json } from '@codemirror/lang-json';
import { PostgreSQL, sql } from '@codemirror/lang-sql';
import { xml } from '@codemirror/lang-xml';
import {
  defaultHighlightStyle,
  forceParsing,
  foldable,
  foldEffect,
  foldGutter,
  foldKeymap,
  syntaxHighlighting,
} from '@codemirror/language';
import type { StateEffect } from '@codemirror/state';
import { searchKeymap } from '@codemirror/search';
import { EditorState } from '@codemirror/state';
import { oneDark } from '@codemirror/theme-one-dark';
import { drawSelection, EditorView, highlightSpecialChars, keymap, lineNumbers } from '@codemirror/view';
import { useEffect, useRef } from 'react';
import { shell } from './shellLanguage';
import type { CodeLanguage } from './xmlFormat';

// Блок кода только для чтения — нагрузки, результаты, тела обмена, SQL чтений: подсветка JSON, XML, SQL и команд curl, номера
// строк, сворачивание вложенного и поиск по Ctrl+F. CodeMirror рисует только видимые строки,
// поэтому и большое тело не подвешивает карточку. Модуль грузится лениво, вместе с карточкой.

const FONT = 'ui-monospace, SFMono-Regular, Menlo, Consolas, monospace';

/** В карточке — не выше 420 px, дальше прокрутка внутри блока. */
const IN_CARD = EditorView.theme({
  '&': { fontSize: '12px', borderRadius: '6px' },
  '.cm-scroller': { maxHeight: '420px', overflow: 'auto', fontFamily: FONT },
  '.cm-gutters': { borderRadius: '6px 0 0 6px' },
});

/**
 * В своей панели — по содержимому, но не выше панели: короткий текст не оставляет пустоты, длинный
 * прокручивается внутри редактора, и тот по-прежнему рисует только видимые строки. {@code reserve} —
 * сколько занято над редактором в той же панели, чтобы она сама не прокручивалась.
 */
function fullTheme(reserve: number) {
  return EditorView.theme({
    '&': { fontSize: '12px', borderRadius: '6px', maxHeight: `calc(100vh - ${100 + reserve}px)` },
    '.cm-scroller': { overflow: 'auto', fontFamily: FONT },
    '.cm-gutters': { borderRadius: '6px 0 0 6px' },
  });
}

/** С этой длины тело в карточке открывается со свёрнутыми глубокими уровнями. */
const LONG = 50;

/**
 * Сворачивает узлы третьего уровня: у JSON видны ключи верхнего уровня и их прямые поля, у SOAP —
 * конверт, {@code Body} и корневой элемент сообщения. Узел узнаётся по отступу в четыре пробела,
 * который дают отступы JSON и XML при показе, а границы свёртки — по синтаксическому разбору.
 */
function foldDeep(view: EditorView): void {
  // Разбор — в сам редактор: foldable смотрит в дерево его состояния, а длинный XML при создании
  // разобран не до конца.
  forceParsing(view, view.state.doc.length, 500);
  const effects: StateEffect<unknown>[] = [];
  for (let number = 1; number <= view.state.doc.lines; number++) {
    const line = view.state.doc.line(number);
    if (/^ {4}\S/.test(line.text)) {
      const range = foldable(view.state, line.from, line.to);
      if (range) {
        effects.push(foldEffect.of(range));
      }
    }
  }
  if (effects.length > 0) {
    view.dispatch({ effects });
  }
}

/** Подсветка по языку тела; SQL — запросы чтения для разбора, диалект PostgreSQL. */
function languageOf(language: CodeLanguage) {
  switch (language) {
    case 'json':
      return json();
    case 'xml':
      return xml();
    case 'sql':
      return sql({ dialect: PostgreSQL });
    case 'shell':
      return shell;
    default:
      return [];
  }
}

export default function CodeView({ text, language, dark, full = false, reserve = 0 }: {
  text: string;
  language: CodeLanguage;
  dark: boolean;
  full?: boolean;
  reserve?: number;
}) {
  const host = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!host.current) {
      return undefined;
    }
    const view = new EditorView({
      parent: host.current,
      state: EditorState.create({
        doc: text,
        extensions: [
          lineNumbers(),
          foldGutter(),
          highlightSpecialChars(),
          drawSelection(),
          EditorView.lineWrapping,
          // Только чтение, но с фокусом: выделение, копирование и поиск работают.
          EditorState.readOnly.of(true),
          keymap.of([...searchKeymap, ...foldKeymap]),
          languageOf(language),
          dark ? oneDark : syntaxHighlighting(defaultHighlightStyle, { fallback: true }),
          full ? fullTheme(reserve) : IN_CARD,
        ],
      }),
    });
    // Длинное тело в карточке — со свёрнутыми глубокими уровнями; развёрнутое в панели — целиком.
    if (!full && language !== 'text' && view.state.doc.lines > LONG) {
      foldDeep(view);
    }
    return () => view.destroy();
  }, [text, language, dark, full, reserve]);
  return <div ref={host} className="code-view" data-testid="code" />;
}
