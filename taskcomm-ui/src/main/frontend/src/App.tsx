import { MoonOutlined, SunOutlined } from '@ant-design/icons';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { Button, ConfigProvider, Layout, Menu, Segmented, Space, Spin, theme, Tooltip, Typography } from 'antd';
import enUS from 'antd/locale/en_US';
import ruRU from 'antd/locale/ru_RU';
import { lazy, Suspense, useCallback, useContext, useState, type ReactNode } from 'react';
import { BrowserRouter, Link, Navigate, Route, Routes, useLocation } from 'react-router';
import { ApiError, appTitle, routerBase } from './api';
import { CreateDrawerProvider, CreateLink } from './createDrawer';
import { EntityDrawerProvider } from './entityDrawer';
import { RefreshControl, refreshInterval } from './refresh';
import { I18nProvider, useI18n, type Language } from './i18n';
import { DarkContext, ThemeContext, useMeta, type Scheme } from './meta';
import { CommunicationCard } from './pages/CommunicationCard';
import { Communications } from './pages/Communications';
import { Execution } from './pages/Execution';
import { Overview } from './pages/Overview';
import { TaskCard } from './pages/TaskCard';
import { Tasks } from './pages/Tasks';

// Создание грузится отдельно: в нём редактор CodeMirror, остальным экранам он не нужен.
const Create = lazy(() => import('./pages/Create').then((module) => ({ default: module.Create })));

const THEME_KEY = 'taskcomm.theme';

export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        refetchInterval: () => refreshInterval(),
        // «Вручную» — и без загрузки при возврате на вкладку: только по кнопке.
        refetchOnWindowFocus: () => refreshInterval() !== false,
        refetchIntervalInBackground: false,
        retry: (failures, error) => !(error instanceof ApiError && error.status < 500) && failures < 2,
      },
    },
  });
}

/** Тема: тёмная по умолчанию, выбор помнится в браузере (docs/adr/0154-ui-shell.md). */
function useStoredScheme(): [Scheme, (scheme: Scheme) => void] {
  const [scheme, setState] = useState<Scheme>(() => {
    try {
      return window.localStorage.getItem(THEME_KEY) === 'light' ? 'light' : 'dark';
    } catch {
      return 'dark';
    }
  });
  const setScheme = useCallback((next: Scheme) => {
    setState(next);
    try {
      window.localStorage.setItem(THEME_KEY, next);
    } catch {
      // не запомнили — выбор действует до перезагрузки
    }
  }, []);
  return [scheme, setScheme];
}

export function App({ client = createQueryClient(), language }: { client?: QueryClient; language?: Language }) {
  return (
    <I18nProvider language={language}>
      <QueryClientProvider client={client}>
        <Themed>
          <BrowserRouter basename={routerBase()}>
            <CreateDrawerProvider>
              <EntityDrawerProvider>
                <Shell />
              </EntityDrawerProvider>
            </CreateDrawerProvider>
          </BrowserRouter>
        </Themed>
      </QueryClientProvider>
    </I18nProvider>
  );
}

function Themed({ children }: { children: ReactNode }) {
  const { language } = useI18n();
  const [scheme, setScheme] = useStoredScheme();
  const dark = scheme === 'dark';
  return (
    <ConfigProvider
      locale={language === 'ru' ? ruRU : enUS}
      theme={{ algorithm: dark ? theme.darkAlgorithm : theme.defaultAlgorithm }}
    >
      <ThemeContext.Provider value={{ scheme, setScheme }}>
        <DarkContext.Provider value={dark}>
          <div className={dark ? 'scheme-dark' : 'scheme-light'}>{children}</div>
        </DarkContext.Provider>
      </ThemeContext.Provider>
    </ConfigProvider>
  );
}

function Shell() {
  const { t } = useI18n();
  const location = useLocation();
  const section = location.pathname.split('/')[1] || 'tasks';
  const { token } = theme.useToken();
  return (
    <Layout style={{ minHeight: '100vh', background: token.colorBgLayout }}>
      <Layout.Header className="header" style={{ background: token.colorBgContainer }}>
        <Link to="/" className="brand">
          <Typography.Title level={4} style={{ margin: 0 }}>
            {appTitle()}
          </Typography.Title>
        </Link>
        <Menu
          mode="horizontal"
          selectedKeys={[section]}
          style={{ flex: 1, minWidth: 0, borderBottom: 'none' }}
          items={[
            { key: 'tasks', label: <Link to="/tasks">{t('nav.tasks')}</Link> },
            { key: 'communications', label: <Link to="/communications">{t('nav.communications')}</Link> },
            { key: 'overview', label: <Link to="/overview">{t('nav.overview')}</Link> },
            { key: 'create', label: <CreateLink>{t('nav.create')}</CreateLink> },
          ]}
        />
        <RefreshControl />
      </Layout.Header>
      <Layout.Content className="content">
        <Suspense fallback={<Spin />}>
          <Routes>
            <Route path="/" element={<Navigate to="/tasks" replace />} />
            <Route path="/overview" element={<Overview />} />
            <Route path="/tasks" element={<Tasks />} />
            <Route path="/tasks/:level/:id" element={<TaskCard />} />
            <Route path="/communications" element={<Communications />} />
            <Route path="/communications/:id" element={<CommunicationCard />} />
            <Route path="/executions/:id" element={<Execution />} />
            <Route path="/create" element={<Create />} />
            <Route path="*" element={<Navigate to="/tasks" replace />} />
          </Routes>
        </Suspense>
      </Layout.Content>
      <Footer />
    </Layout>
  );
}

/** Подвал: экземпляр, версия, тема и язык — справа. */
function Footer() {
  const { t, language, setLanguage } = useI18n();
  const meta = useMeta();
  const { scheme, setScheme } = useContext(ThemeContext);
  const { token } = theme.useToken();
  return (
    <Layout.Footer className="footer" style={{ background: token.colorBgContainer }}>
      <Space size={16} wrap>
        {meta.data?.instance && (
          <Tooltip title={t('footer.instanceHint')}>
            <Typography.Text type="secondary">{t('footer.instance', { instance: meta.data.instance })}</Typography.Text>
          </Tooltip>
        )}
        {meta.data?.version && (
          <Typography.Text type="secondary">{t('common.version', { version: meta.data.version })}</Typography.Text>
        )}
        <Segmented<Scheme>
          size="small"
          value={scheme}
          onChange={setScheme}
          options={[
            { value: 'light', icon: <SunOutlined />, title: t('theme.light') },
            { value: 'dark', icon: <MoonOutlined />, title: t('theme.dark') },
          ]}
        />
        <Button size="small" onClick={() => setLanguage(language === 'ru' ? 'en' : 'ru')}>
          {t('lang.switch')}
        </Button>
      </Space>
    </Layout.Footer>
  );
}
