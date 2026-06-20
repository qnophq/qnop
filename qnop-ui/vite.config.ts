import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { fileURLToPath, URL } from 'node:url';

// https://vite.dev/config/
//
// Single-origin in production (the SPA is served by the Spring app), so the
// API base path is the relative `/api/v1`. In dev, proxy API + OIDC handshake
// routes to the backend. `changeOrigin: false` on the OIDC routes keeps the
// Host header as the dev server so the provider redirect_uri stays correct.
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
  server: {
    proxy: {
      '/api': 'http://localhost:8080',
      '/oauth2': { target: 'http://localhost:8080', changeOrigin: false },
      '/login/oauth2': { target: 'http://localhost:8080', changeOrigin: false },
    },
  },
});
