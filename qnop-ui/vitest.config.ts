import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';
import { fileURLToPath, URL } from 'node:url';

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
      url: fileURLToPath(new URL('./src/shims/url.ts', import.meta.url)),
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    globals: true,
    server: {
      deps: {
        inline: [/@mui\//],
      },
    },
    coverage: {
      provider: 'v8',
      reporter: ['text', 'html'],
      // Coverage is scoped to the logic worth unit-testing: stores, guards,
      // refresh, hooks, theme and utils. Thin wiring (api/config.ts axios
      // setup, queryClient), presentational stubs (pages, AppShell) and the
      // bootstrap (main, router) are covered by component/visual/E2E tests as
      // their real screens land (#102/#103), not by markup assertions here.
      include: [
        'src/stores/**/*.ts',
        'src/utils/**/*.ts',
        'src/api/refresh.ts',
        'src/api/hooks/**/*.ts',
        'src/components/auth/**/*.tsx',
        'src/components/shell/navConfig.tsx',
        'src/theme/**/*.ts',
      ],
      thresholds: {
        lines: 80,
        functions: 80,
        branches: 80,
        statements: 80,
      },
    },
  },
});
