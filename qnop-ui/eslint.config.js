import js from '@eslint/js';
import globals from 'globals';
import jsxA11y from 'eslint-plugin-jsx-a11y';
import reactHooks from 'eslint-plugin-react-hooks';
import reactRefresh from 'eslint-plugin-react-refresh';
import tseslint from 'typescript-eslint';
import { defineConfig, globalIgnores } from 'eslint/config';

// Props whose string value is shown to the user (or read out by assistive technology).
const TEXT_PROPS =
  '/^(label|title|placeholder|alt|aria-label|aria-description|helperText|subtitle|description|tooltip|text|message|primary|secondary|confirmLabel|cancelLabel|emptyText|caption|heading)$/';

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
      'src/components/banner/**/*.tsx',
      'src/components/limits/**/*.tsx',
      'src/components/errors/**/*.tsx',
      'src/components/notifications/**/*.tsx',
      'src/components/branding/**/*.tsx',
      'src/components/ClearableSearchField.tsx',
      'src/components/TimezonePicker.tsx',
      'src/pages/errors/**/*.tsx',
      'src/pages/messages/**/*.tsx',
      'src/pages/attachments/**/*.tsx',
      'src/components/dashboard/**/*.tsx',
      'src/pages/HomePage.tsx',
      'src/components/my-teams/**/*.tsx',
      'src/pages/my-teams/**/*.tsx',
      'src/components/people/**/*.tsx',
      'src/pages/UserProfilePage.tsx',
      'src/pages/TeamProfilePage.tsx',
      'src/components/audit/**/*.tsx',
      'src/pages/audit/**/*.tsx',
      'src/pages/search/**/*.tsx',
      'src/components/admin/**/*.tsx',
      'src/pages/admin/**/*.tsx',
    ],
    ignores: ['**/*.test.tsx'],
    rules: {
      // Literal text reaching the DOM: as a JSX child — directly, in a template literal, or as a
      // branch of a ternary/&& — or as the value of a text-bearing prop, quoted, in braces or as a
      // text-bearing key of an object prop.
      // String concatenation is not caught; review keeps that out (it would be the odd one).
      'no-restricted-syntax': [
        'error',
        ...[
          'JSXText[value=/[A-Za-z]{2,}/]',
          'JSXElement > JSXExpressionContainer > Literal[value=/[A-Za-z]{2,}/]',
          'JSXElement > JSXExpressionContainer > TemplateLiteral > TemplateElement[value.raw=/[A-Za-z]{2,}/]',
          'JSXElement > JSXExpressionContainer > :matches(ConditionalExpression, LogicalExpression) > Literal[value=/[A-Za-z]{2,}/]',
          `JSXAttribute[name.name=${TEXT_PROPS}] > Literal[value=/[A-Za-z]{2,}/]`,
          `JSXAttribute[name.name=${TEXT_PROPS}] > JSXExpressionContainer > Literal[value=/[A-Za-z]{2,}/]`,
          `JSXAttribute[name.name=${TEXT_PROPS}] > JSXExpressionContainer > TemplateLiteral > TemplateElement[value.raw=/[A-Za-z]{2,}/]`,
          `JSXAttribute[name.name=${TEXT_PROPS}] > JSXExpressionContainer > :matches(ConditionalExpression, LogicalExpression) > Literal[value=/[A-Za-z]{2,}/]`,
          // Text inside an object passed as a prop, e.g. primaryAction={{ label: 'Retry' }}.
          `JSXAttribute ObjectExpression > Property[key.name=${TEXT_PROPS}] > Literal[value=/[A-Za-z]{2,}/]`,
          `JSXAttribute ObjectExpression > Property[key.name=${TEXT_PROPS}] > TemplateLiteral > TemplateElement[value.raw=/[A-Za-z]{2,}/]`,
        ].map((selector) => ({
          selector,
          message: 'User-facing text belongs in an i18n catalog — use t() (ADR-0063).',
        })),
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
