import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

// Сборка кладёт статику в build/frontend модуля, Gradle упаковывает её в jar как
// META-INF/taskcomm/ui (docs/adr/0149-web-interface-module.md). Пути ассетов относительные:
// контроллер стартера вставляет в index.html <base href> с путём интерфейса.
export default defineConfig({
  base: './',
  plugins: [react()],
  build: {
    outDir: '../../../build/frontend',
    emptyOutDir: true,
    assetsDir: 'assets',
    // Ant Design — сознательно тяжёлый кусок (docs/adr/0152-web-interface-frontend-stack.md).
    chunkSizeWarningLimit: 2000,
  },
  server: {
    // Разработка: приложение с taskcomm на 8080, REST по пути по умолчанию.
    proxy: {
      '/tc/api': 'http://localhost:8080',
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.test.{ts,tsx}'],
  },
});
