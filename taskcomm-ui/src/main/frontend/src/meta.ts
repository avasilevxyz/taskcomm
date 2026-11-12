import { useQuery } from '@tanstack/react-query';
import { createContext } from 'react';
import { getJson, type Meta } from './api';

/** Описание экземпляра: читается один раз и не опрашивается — процессоры в работе не меняются. */
export function useMeta() {
  return useQuery({ queryKey: ['meta'], queryFn: () => getJson<Meta>('/meta'), refetchInterval: false });
}

/** Тёмная ли тема — для того, что Ant Design не красит сам, например редактора. */
export const DarkContext = createContext(false);
