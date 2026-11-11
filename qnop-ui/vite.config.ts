import { defineConfig, type Plugin } from 'vite';
import react from '@vitejs/plugin-react';
import { randomBytes } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
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

/**
 * Nonce-based styles (issue #913, ADR-0040 amendment). The production build
 * leaves `__CSP_NONCE__` in index.html — on the `<meta property="csp-nonce">`
 * the app reads and on the document's own tags — and the backend swaps in a
 * fresh nonce per request, the same one its `style-src` allows.
 *
 * Dev and preview enforce the same rule so a style that only works with
 * `'unsafe-inline'` breaks here first, not after a release:
 * - dev: one random nonce per server start, `style-src` only (HMR needs the
 *   rest of the dev server's freedom);
 * - preview (the Playwright suite): the full SPA policy, with a fresh nonce per
 *   index.html response, exactly as the backend serves it.
 */
const CSP_NONCE_PLACEHOLDER = '__CSP_NONCE__';

/** Mirrors PathAwareCspHeaderWriter's SPA policy (qnop-app); keep the two in step. */
function spaPolicy(nonce: string): string {
  return (
    `default-src 'self'; script-src 'self'; style-src 'self' 'nonce-${nonce}'; ` +
    "img-src 'self' data: blob:; font-src 'self' data:; connect-src 'self'; " +
    "worker-src 'self' blob:; frame-ancestors 'none'; base-uri 'self'; " +
    "form-action 'self'; object-src 'none'"
  );
}

function freshNonce(): string {
  return randomBytes(16).toString('base64');
}

function cspNonce(): Plugin {
  const devNonce = freshNonce();
  let indexPath = '';
  return {
    name: 'qnop-csp-nonce',
    config(_, { command }) {
      return { html: { cspNonce: command === 'build' ? CSP_NONCE_PLACEHOLDER : devNonce } };
    },
    configResolved(config) {
      indexPath = path.resolve(config.root, config.build.outDir, 'index.html');
    },
    configureServer(server) {
      server.middlewares.use((_req, res, next) => {
        res.setHeader('Content-Security-Policy', `style-src 'self' 'nonce-${devNonce}'`);
        next();
      });
    },
    configurePreviewServer(server) {
      // Runs before the preview proxy and static files: a page navigation
      // outside the proxied backend paths gets index.html, rendered per request.
      server.middlewares.use((req, res, next) => {
        const url = new URL(req.url ?? '/', 'http://preview');
        const isNavigation =
          req.method === 'GET' &&
          (req.headers.accept ?? '').includes('text/html') &&
          !Object.keys(proxy).some((prefix) => url.pathname.startsWith(prefix)) &&
          (path.extname(url.pathname) === '' || url.pathname.endsWith('.html'));
        if (!isNavigation) return next();
        readFile(indexPath, 'utf8').then(
          (template) => {
            const nonce = freshNonce();
            res.setHeader('Content-Type', 'text/html; charset=utf-8');
            res.setHeader('Cache-Control', 'no-store');
            res.setHeader('Content-Security-Policy', spaPolicy(nonce));
            res.end(template.replaceAll(CSP_NONCE_PLACEHOLDER, nonce));
          },
          (error: unknown) => next(error),
        );
      });
    },
  };
}

export default defineConfig({
  plugins: [react(), cspNonce()],
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
