import js from '@eslint/js';
import globals from 'globals';
import jsxA11y from 'eslint-plugin-jsx-a11y';
import reactHooks from 'eslint-plugin-react-hooks';
import reactRefresh from 'eslint-plugin-react-refresh';
import tseslint from 'typescript-eslint';
import { defineConfig, globalIgnores } from 'eslint/config';

export default defineConfig([
  // dist: build output. coverage: vitest report. src/api/generated: typescript-axios output.
  globalIgnores(['dist', 'coverage', 'src/api/generated']),
  {
    files: ['**/*.{ts,tsx}'],
    extends: [
      js.configs.recommended,
      tseslint.configs.recommended,
      jsxA11y.flatConfigs.recommended,
      reactHooks.configs.flat.recommended,
      reactRefresh.configs.vite,
    ],
    languageOptions: {
      globals: globals.browser,
    },
    rules: {
      '@typescript-eslint/no-unused-vars': [
        'error',
        {
          argsIgnorePattern: '^_',
          varsIgnorePattern: '^_',
          caughtErrorsIgnorePattern: '^_',
          ignoreRestSiblings: true,
        },
      ],
    },
  },
  {
    // Externalized surfaces (issue #464, ADR-0063): user-facing text comes from the i18n
    // catalogs, never a literal. Each surface joins this list once it is translated; the
    // remaining ones follow in the coverage sweep.
    files: [
      'src/pages/auth/**/*.tsx',
      'src/components/auth/**/*.tsx',
      'src/components/shell/**/*.tsx',
      'src/components/i18n/**/*.tsx',
      'src/components/profile/**/*.tsx',
      'src/pages/ProfilePage.tsx',
    ],
    ignores: ['**/*.test.tsx'],
    rules: {
      'no-restricted-syntax': [
        'error',
        {
          selector: 'JSXText[value=/[A-Za-z]{2,}/]',
          message: 'User-facing text belongs in an i18n catalog — use t() (ADR-0063).',
        },
        {
          selector: 'JSXElement > JSXExpressionContainer > Literal[value=/[A-Za-z]{2,}/]',
          message: 'User-facing text belongs in an i18n catalog — use t() (ADR-0063).',
        },
        {
          selector:
            'JSXAttribute[name.name=/^(label|title|placeholder|alt|aria-label|helperText|subtitle|description|tooltip)$/] > Literal[value=/[A-Za-z]{2,}/]',
          message: 'User-facing attribute text belongs in an i18n catalog — use t() (ADR-0063).',
        },
      ],
    },
  },
  {
    // These modules legitimately export non-components alongside components
    // (router config, Zustand stores, theme helpers, tests), so the
    // Fast-Refresh-only rule does not apply.
    files: [
      'src/main.tsx',
      'src/router/**/*.{ts,tsx}',
      'src/stores/**/*.{ts,tsx}',
      'src/theme/**/*.{ts,tsx}',
      '**/*.test.{ts,tsx}',
    ],
    rules: {
      'react-refresh/only-export-components': 'off',
    },
  },
  {
    // Playwright fixtures (issue #725) take a `use` callback — the test
    // runner's, not React's — and no React code lives here.
    files: ['e2e/**/*.ts', 'playwright.config.ts'],
    rules: {
      'react-hooks/rules-of-hooks': 'off',
    },
  },
]);
