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
      // Scope grows per the #352 coverage waves (auth → viewer → admin → shell),
      // each wave adding its slice here alongside the tests that cover it.
      include: [
        'src/stores/**/*.ts',
        'src/utils/**/*.ts',
        'src/api/refresh.ts',
        'src/api/auth.ts',
        'src/api/hooks/**/*.ts',
        // Auth guards are logic (unit-tested); the presentational auth
        // components (AuthLayout, OidcButtons, PasswordField, the strength
        // meter) are verified visually, not by markup assertions.
        'src/components/auth/ProtectedRoute.tsx',
        'src/components/auth/AdminRoute.tsx',
        'src/components/auth/RoleRoute.tsx',
        'src/components/auth/TeamLeadRoute.tsx',
        'src/components/auth/AuthHydrationBoundary.tsx',
        'src/components/shell/navConfig.tsx',
        // Auth screens — the critical entry flow (issue #352, wave 1).
        'src/pages/auth/**/*.tsx',
        // Admin screens — users, teams, settings, branding and OIDC (issue
        // #352, wave 3). The pages own the orchestration/state; their heavy
        // child dialogs/tables/forms stay on their own component tests. (The
        // mail admin pages carry their own tests but predate this gate and are
        // not yet at the 80% bar, so they stay off the allowlist for now.)
        'src/pages/admin/UsersPage.tsx',
        'src/pages/admin/TeamsPage.tsx',
        'src/pages/admin/TeamDetailPage.tsx',
        'src/pages/admin/SettingsPage.tsx',
        'src/pages/admin/BrandingPage.tsx',
        'src/pages/admin/OidcProvidersPage.tsx',
        // Team-lead self-management surface (issue #470): the "My Teams" landing
        // and detail pages own the orchestration/state; the add-member dialog
        // stays on its own component test.
        'src/pages/my-teams/MyTeamsPage.tsx',
        'src/pages/my-teams/MyTeamDetailPage.tsx',
        // The viewer's pure anchor-building logic (#250) plus the document
        // surface (issue #352, wave 2): the pdf.js loader/canvas wiring and the
        // scrollable page stack with its scroll spy and rubber-band selection.
        // SurfacePage and the presentational layers stay on component/E2E tests.
        'src/components/reviews/viewer/anchoring.ts',
        'src/components/reviews/viewer/usePdfDocument.ts',
        'src/components/reviews/viewer/PageCanvas.tsx',
        'src/components/reviews/viewer/DocumentViewer.tsx',
        'src/components/reviews/viewer/RegionSelectLayer.tsx',
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
