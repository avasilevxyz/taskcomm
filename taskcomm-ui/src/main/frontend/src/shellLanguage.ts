import { StreamLanguage, type StringStream } from '@codemirror/language';

// Подсветка команды curl: команда, флаги, строки в кавычках и перенос строки «\». Своя и маленькая —
// ради одной команды не стоит тянуть пакет режимов оболочки: CodeMirror даёт StreamLanguage в уже
// подключённом @codemirror/language.

interface State {
  /** Первое слово команды — имя программы. */
  command: boolean;
  /** Открытая кавычка: тело запроса в кавычках занимает несколько строк. */
  quote: string | null;
}

/** Строка в кавычках до закрывающей или до конца строки, если она продолжится на следующей. */
function quoted(stream: StringStream, state: State): string {
  while (!stream.eol()) {
    const next = stream.next();
    if (next === state.quote) {
      state.quote = null;
      return 'string';
    }
    if (next === '\\' && state.quote === '"') {
      stream.next();
    }
  }
  return 'string';
}

function token(stream: StringStream, state: State): string | null {
  if (state.quote !== null) {
    return quoted(stream, state);
  }
  if (stream.eatSpace()) {
    return null;
  }
  const next = stream.peek();
  if (next === '#') {
    stream.skipToEnd();
    return 'comment';
  }
  if (next === "'" || next === '"') {
    state.quote = stream.next() ?? null;
    return quoted(stream, state);
  }
  if (stream.match('\\')) {
    return 'operator';
  }
  stream.eatWhile(/[^\s'"]/);
  if (state.command) {
    state.command = false;
    return 'keyword';
  }
  return stream.current().startsWith('-') ? 'attributeName' : null;
}

/** Язык команды оболочки — для панели «curl». */
export const shell = StreamLanguage.define<State>({
  name: 'shell',
  startState: () => ({ command: true, quote: null }),
  token,
  copyState: (state) => ({ ...state }),
});
