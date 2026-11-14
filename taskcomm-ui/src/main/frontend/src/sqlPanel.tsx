import { ConsoleSqlOutlined } from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { Button, Flex, Tooltip, Typography } from 'antd';
import { useContext } from 'react';
import { getJson } from './api';
import { CodeBody, CopyButton, Loadable } from './common';
import type { TableRequest } from './curlPanel';
import { useI18n } from './i18n';
import { DarkContext } from './meta';
import { useOpenPanel } from './panels';

// SQL, которым сервер прочитал строки таблицы (docs/adr/0165-read-sql.md): у каждого чтения есть
// выход «…/sql» с теми же параметрами, он отдаёт запрос с подставленными значениями. Собирает его
// сервер тем же кодом, что выполняет чтение, — в интерфейсе запрос не строится.

interface Sql {
  sql: string;
}

/** Запрос SQL таблицы — один на панель и её «Копировать». */
function useSql(request: TableRequest) {
  return useQuery({
    queryKey: ['sql', request.path, request.params],
    queryFn: () => getJson<Sql>(`${request.path}/sql`, request.params),
    refetchInterval: false,
    staleTime: Infinity,
  });
}

/** Содержимое панели «SQL»: подсказка про внутреннюю схему и запрос. */
function SqlView({ request }: { request: TableRequest }) {
  const { t } = useI18n();
  const dark = useContext(DarkContext);
  const query = useSql(request);
  return (
    <Flex vertical gap={8}>
      <Typography.Text type="secondary">{t('sql.hint')}</Typography.Text>
      <Loadable loading={query.isLoading} error={query.error}>
        {/* Подсказка над запросом — её высоту редактор вычитает, чтобы панель не прокручивалась сама. */}
        {() => <CodeBody text={query.data?.sql ?? ''} language="sql" dark={dark} full reserve={64} />}
      </Loadable>
    </Flex>
  );
}

/** «Копировать» в шапке панели — когда запрос пришёл. */
function SqlCopy({ request }: { request: TableRequest }) {
  const query = useSql(request);
  return query.data ? <CopyButton text={query.data.sql} /> : null;
}

/**
 * Значок над таблицей рядом с «curl»: SQL, которым сервер прочитал её строки, — снимок на момент
 * щелчка. У списков страницами — первая страница, как у curl.
 */
export function TableSqlButton({ request }: { request: TableRequest }) {
  const { t } = useI18n();
  const openPanel = useOpenPanel();
  const onClick = () =>
    openPanel({
      kind: 'code',
      label: t('sql.title'),
      extra: <SqlCopy request={request} />,
      render: () => <SqlView request={request} />,
    });
  return (
    <Tooltip title={t('sql.title')}>
      <Button size="small" type="text" icon={<ConsoleSqlOutlined />} aria-label={t('sql.title')} onClick={onClick} />
    </Tooltip>
  );
}
