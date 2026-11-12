import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { Button, ConfigProvider, Layout, Menu, Space, Spin, theme, Typography } from 'antd';
import enUS from 'antd/locale/en_US';
import ruRU from 'antd/locale/ru_RU';
import { lazy, Suspense, useEffect, useState, type ReactNode } from 'react';
import { BrowserRouter, Link, Navigate, Route, Routes, useLocation } from 'react-router';
import { ApiError, routerBase } from './api';
import { I18nProvider, useI18n, type Language } from './i18n';
import { DarkContext, useMeta } from './meta';
import { CommunicationCard } from './pages/CommunicationCard';
import { Communications } from './pages/Communications';
import { Execution } from './pages/Execution';
import { Overview } from './pages/Overview';
import { TaskCard } from './pages/TaskCard';
import { Tasks } from './pages/Tasks';

// Создание грузится отдельно: в нём редактор CodeMirror, остальным экранам он не нужен.
const Create = lazy(() => import('./pages/Create').then((module) => ({ default: module.Create })));

/** Опрос открытого экрана (docs/adr/0152-web-interface-frontend-stack.md). */
export const REFRESH_MILLIS = 10_000;

export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        refetchInterval: REFRESH_MILLIS,
        refetchIntervalInBackground: false,
        retry: (failures, error) => !(error instanceof ApiError && error.status < 500) && failures < 2,
      },
    },
  });
}

function useDarkScheme(): boolean {
  const query = '(prefers-color-scheme: dark)';
  const [dark, setDark] = useState(() => window.matchMedia?.(query).matches ?? false);
  useEffect(() => {
    const media = window.matchMedia?.(query);
    if (!media) {
      return undefined;
    }
    const listener = (event: MediaQueryListEvent) => setDark(event.matches);
    media.addEventListener('change', listener);
    return () => media.removeEventListener('change', listener);
  }, []);
  return dark;
}

export function App({ client = createQueryClient(), language }: { client?: QueryClient; language?: Language }) {
  return (
    <I18nProvider language={language}>
      <QueryClientProvider client={client}>
        <Themed>
          <BrowserRouter basename={routerBase()}>
            <Shell />
          </BrowserRouter>
        </Themed>
      </QueryClientProvider>
    </I18nProvider>
  );
}

function Themed({ children }: { children: ReactNode }) {
  const { language } = useI18n();
  const dark = useDarkScheme();
  return (
    <ConfigProvider
      locale={language === 'ru' ? ruRU : enUS}
      theme={{ algorithm: dark ? theme.darkAlgorithm : theme.defaultAlgorithm }}
    >
      <DarkContext.Provider value={dark}>
        <div className={dark ? 'scheme-dark' : 'scheme-light'}>{children}</div>
      </DarkContext.Provider>
    </ConfigProvider>
  );
}

function Shell() {
  const { t, language, setLanguage } = useI18n();
  const meta = useMeta();
  const location = useLocation();
  const section = location.pathname.split('/')[1] || 'overview';
  const { token } = theme.useToken();
  return (
    <Layout style={{ minHeight: '100vh', background: token.colorBgLayout }}>
      <Layout.Header className="header" style={{ background: token.colorBgContainer }}>
        <Link to="/" className="brand">
          <Typography.Title level={4} style={{ margin: 0 }}>
            {t('app.title')}
          </Typography.Title>
        </Link>
        <Menu
          mode="horizontal"
          selectedKeys={[section]}
          style={{ flex: 1, minWidth: 0, borderBottom: 'none' }}
          items={[
            { key: 'overview', label: <Link to="/">{t('nav.overview')}</Link> },
            { key: 'tasks', label: <Link to="/tasks">{t('nav.tasks')}</Link> },
            { key: 'communications', label: <Link to="/communications">{t('nav.communications')}</Link> },
            { key: 'create', label: <Link to="/create">{t('nav.create')}</Link> },
          ]}
        />
        <Space>
          {meta.data?.version && (
            <Typography.Text type="secondary">{t('common.version', { version: meta.data.version })}</Typography.Text>
          )}
          <Button size="small" onClick={() => setLanguage(language === 'ru' ? 'en' : 'ru')}>
            {t('lang.switch')}
          </Button>
        </Space>
      </Layout.Header>
      <Layout.Content className="content">
        <Suspense fallback={<Spin />}>
          <Routes>
            <Route path="/" element={<Overview />} />
            <Route path="/tasks" element={<Tasks />} />
            <Route path="/tasks/:level/:id" element={<TaskCard />} />
            <Route path="/communications" element={<Communications />} />
            <Route path="/communications/:id" element={<CommunicationCard />} />
            <Route path="/executions/:id" element={<Execution />} />
            <Route path="/create" element={<Create />} />
            <Route path="*" element={<Navigate to="/" replace />} />
          </Routes>
        </Suspense>
      </Layout.Content>
    </Layout>
  );
}
