import { json, jsonParseLinter } from '@codemirror/lang-json';
import { EditorState } from '@codemirror/state';
import { oneDark } from '@codemirror/theme-one-dark';
import { EditorView } from '@codemirror/view';
import { basicSetup } from 'codemirror';
import { linter, lintGutter } from '@codemirror/lint';
import { useEffect, useRef } from 'react';

/**
 * Редактор нагрузки JSON на CodeMirror 6 (docs/adr/0152-web-interface-frontend-stack.md): подсветка,
 * номера строк, ошибка разбора на месте. Значение снаружи меняет текст, только если он другой —
 * иначе каждое нажатие сбрасывало бы курсор.
 */
export function JsonEditor({ value, onChange, dark, label }: {
  value: string;
  onChange: (value: string) => void;
  dark: boolean;
  label: string;
}) {
  const host = useRef<HTMLDivElement>(null);
  const view = useRef<EditorView | null>(null);
  const changed = useRef(onChange);
  changed.current = onChange;

  useEffect(() => {
    if (!host.current) {
      return undefined;
    }
    const editor = new EditorView({
      parent: host.current,
      state: EditorState.create({
        doc: value,
        extensions: [
          basicSetup,
          json(),
          linter(jsonParseLinter()),
          lintGutter(),
          EditorView.contentAttributes.of({ 'aria-label': label }),
          EditorView.updateListener.of((update) => {
            if (update.docChanged) {
              changed.current(update.state.doc.toString());
            }
          }),
          ...(dark ? [oneDark] : []),
        ],
      }),
    });
    view.current = editor;
    return () => {
      editor.destroy();
      view.current = null;
    };
    // Редактор пересоздаётся только при смене темы; текст синхронизирует эффект ниже.
  }, [dark]);

  useEffect(() => {
    const editor = view.current;
    if (editor && editor.state.doc.toString() !== value) {
      editor.dispatch({ changes: { from: 0, to: editor.state.doc.length, insert: value } });
    }
  }, [value]);

  return <div ref={host} className="json-editor" />;
}
