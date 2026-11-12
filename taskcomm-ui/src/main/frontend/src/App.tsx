import { useEffect, useState } from 'react';
import { ApiError, getJson, type Meta } from './api';

type State =
  | { kind: 'loading' }
  | { kind: 'ready'; meta: Meta }
  | { kind: 'failed'; error: ApiError | Error };

// Каркас интерфейса (этап 11b): читает описание экземпляра и показывает, что есть.
// Экраны обзора, задач, коммуникаций, дела и создания — этап 11c.
export function App() {
  const [state, setState] = useState<State>({ kind: 'loading' });

  useEffect(() => {
    getJson<Meta>('/meta')
      .then((meta) => setState({ kind: 'ready', meta }))
      .catch((error: unknown) =>
        setState({ kind: 'failed', error: error instanceof Error ? error : new Error(String(error)) }),
      );
  }, []);

  return (
    <main>
      <header>
        <h1>taskcomm</h1>
        {state.kind === 'ready' && state.meta.version && <span className="version">{state.meta.version}</span>}
      </header>
      {state.kind === 'loading' && <p>Загрузка…</p>}
      {state.kind === 'failed' && <Failure error={state.error} />}
      {state.kind === 'ready' && <Instance meta={state.meta} />}
    </main>
  );
}

function Failure({ error }: { error: ApiError | Error }) {
  if (error instanceof ApiError && error.forbidden) {
    return <p role="alert">Нет прав на чтение taskcomm.</p>;
  }
  return <p role="alert">Не удалось прочитать описание экземпляра: {error.message}</p>;
}

function Instance({ meta }: { meta: Meta }) {
  return (
    <>
      <section>
        <h2>Типы задач</h2>
        {meta.tasks ? (
          <ul>
            {meta.tasks.types.map((type) => (
              <li key={type.type}>
                {type.type} <small>{type.contract}</small>
              </li>
            ))}
          </ul>
        ) : (
          <p>Подсистема задач выключена.</p>
        )}
      </section>
      <section>
        <h2>Внешние системы</h2>
        {meta.communications ? (
          <ul>
            {meta.communications.systems.map((system) => (
              <li key={system.system}>
                {system.system} <small>{system.transport}</small>
              </li>
            ))}
          </ul>
        ) : (
          <p>Подсистема коммуникаций выключена.</p>
        )}
      </section>
    </>
  );
}
