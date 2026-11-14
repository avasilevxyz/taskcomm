import { CodeOutlined } from '@ant-design/icons';
import { Button, Flex, Tooltip, Typography } from 'antd';
import { useContext } from 'react';
import { apiBase, query, type Params } from './api';
import { CodeBody, CopyButton } from './common';
import { curlCommand } from './curl';
import { useI18n } from './i18n';
import { DarkContext } from './meta';
import { useOpenPanel } from './panels';

/** Запрос, которым таблица получила свои данные: путь REST и параметры. */
export interface TableRequest {
  path: string;
  params: Params;
}

/** Полный адрес запроса — тот же, что у интерфейса. */
export function requestUrl(path: string, params: Params): string {
  return window.location.origin + apiBase() + path + query(params);
}

/** Содержимое панели «curl»: подсказка про авторизацию и команда. */
export function CurlBody({ command }: { command: string }) {
  const { t } = useI18n();
  const dark = useContext(DarkContext);
  return (
    <Flex vertical gap={8}>
      <Typography.Text type="secondary">{t('curl.hint')}</Typography.Text>
      {/* Подсказка над командой — её высоту редактор вычитает, чтобы панель не прокручивалась сама. */}
      <CodeBody text={command} language="shell" dark={dark} full reserve={48} />
    </Flex>
  );
}

/**
 * Значок над таблицей рядом со «Столбцами»: команда curl, которой таблица получила данные, — снимок
 * на момент щелчка. У списков страницами — первая страница: дальше идут по курсору из ответа.
 */
export function TableCurlButton({ request }: { request: TableRequest }) {
  const { t } = useI18n();
  const openPanel = useOpenPanel();
  const onClick = () => {
    const command = curlCommand({ method: 'GET', url: requestUrl(request.path, request.params) });
    openPanel({
      kind: 'code',
      label: t('curl.title'),
      extra: <CopyButton text={command} />,
      render: () => <CurlBody command={command} />,
    });
  };
  return (
    <Tooltip title={t('curl.title')}>
      <Button size="small" type="text" icon={<CodeOutlined />} aria-label={t('curl.title')} onClick={onClick} />
    </Tooltip>
  );
}
