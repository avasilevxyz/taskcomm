import { useQuery } from '@tanstack/react-query';
import { createContext } from 'react';
import { getJson, type Meta } from './api';

/** Описание экземпляра: читается один раз и не опрашивается — процессоры в работе не меняются. */
export function useMeta() {
  return useQuery({ queryKey: ['meta'], queryFn: () => getJson<Meta>('/meta'), refetchInterval: false });
}

/** Тёмная ли тема — для того, что Ant Design не красит сам, например редактора. */
export const DarkContext = createContext(false);

export type Scheme = 'light' | 'dark';

/** Выбранная тема и её переключение (docs/adr/0154-ui-shell.md). */
export const ThemeContext = createContext<{ scheme: Scheme; setScheme: (scheme: Scheme) => void }>({
  scheme: 'dark',
  setScheme: () => undefined,
});
