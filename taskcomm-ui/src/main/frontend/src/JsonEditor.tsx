import { json, jsonParseLinter } from '@codemirror/lang-json';
import { Compartment, EditorState } from '@codemirror/state';
import { oneDark } from '@codemirror/theme-one-dark';
import { EditorView } from '@codemirror/view';
import { basicSetup } from 'codemirror';
import { linter, lintGutter, type Diagnostic } from '@codemirror/lint';
import { useEffect, useRef } from 'react';
import type { PayloadShape } from './api';
import { checkShape, type ShapeProblem } from './payloadShape';

/**
 * Редактор нагрузки JSON на CodeMirror 6 (docs/adr/0152-web-interface-frontend-stack.md): подсветка,
 * номера строк, ошибка разбора на месте. Значение снаружи меняет текст, только если он другой —
 * иначе каждое нажатие сбрасывало бы курсор. Со структурой нагрузки — ещё и замечания по ключам
 * и типам значений (docs/adr/0160-payload-shape.md): предупреждениями, не ошибками.
 */
export function JsonEditor({ value, onChange, dark, label, shape, describe }: {
  value: string;
  onChange: (value: string) => void;
  dark: boolean;
  label: string;
  shape?: PayloadShape;
  describe?: (problem: ShapeProblem) => string;
}) {
  const host = useRef<HTMLDivElement>(null);
  const view = useRef<EditorView | null>(null);
  const changed = useRef(onChange);
  changed.current = onChange;
  const check = useRef(new Compartment());
  const structure = () =>
    shape && describe
      ? linter((editor) =>
          checkShape(editor.state.doc.toString(), shape).map(
            (problem): Diagnostic => ({ from: problem.from, to: problem.to, severity: 'warning', message: describe(problem) }),
          ),
        )
      : [];

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
          check.current.of(structure()),
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

  // Другая структура — другая проверка, без пересоздания редактора и потери курсора.
  useEffect(() => {
    view.current?.dispatch({ effects: check.current.reconfigure(structure()) });
    // structure зависит только от shape и describe.
  }, [shape, describe]);

  useEffect(() => {
    const editor = view.current;
    if (editor && editor.state.doc.toString() !== value) {
      editor.dispatch({ changes: { from: 0, to: editor.state.doc.length, insert: value } });
    }
  }, [value]);

  return <div ref={host} className="json-editor" />;
}
