import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { fileURLToPath, URL } from 'node:url';

// https://vite.dev/config/
//
// Single-origin in production (the SPA is served by the Spring app), so the
// API base path is the relative `/api/v1`. In dev, proxy API + OIDC handshake
// routes to the backend. `changeOrigin: false` on the OIDC routes keeps the
// Host header as the dev server so the provider redirect_uri stays correct.
//
// `/t` is the usage-tracking proxy (issue #666). It deliberately sits outside
// /api — it is not part of the published REST contract — which also means it is
// not covered by the `/api` rule above and has to be named. In production the
// SPA is served by the backend itself (ADR-0040), so there this is one origin
// and no proxying happens at all; without this line measurement works in a
// deployment and silently 404s in dev, which is the worst of both.
//
// `QNOP_API_URL` moves the proxy target (issue #725): the Playwright run points
// the dev server at whatever backend it has — the CI smoke stack, a local
// container on another port — without editing this file.
const apiUrl = process.env.QNOP_API_URL ?? 'http://localhost:8080';

const proxy = {
  '/api': apiUrl,
  '/t': apiUrl,
  '/oauth2': { target: apiUrl, changeOrigin: false },
  '/login/oauth2': { target: apiUrl, changeOrigin: false },
};

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
      // The generated typescript-axios client imports Node's `url`; map it to a
      // browser shim that re-exports the global URL / URLSearchParams.
      url: fileURLToPath(new URL('./src/shims/url.ts', import.meta.url)),
    },
  },
  server: { proxy },
  // The Playwright suite (issue #725) serves the built bundle through
  // `vite preview` — no per-route cold transform, and the same code a
  // deployment ships — so the preview server proxies the API exactly like dev.
  preview: { proxy },
});
