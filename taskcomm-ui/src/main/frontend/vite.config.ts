import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

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
  },
  server: {
    // Разработка: приложение с taskcomm на 8080, REST по пути по умолчанию.
    proxy: {
      '/tc/api': 'http://localhost:8080',
    },
  },
});
