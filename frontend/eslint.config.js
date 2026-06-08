import js from '@eslint/js'
import tsplugin from '@typescript-eslint/eslint-plugin'
import tsparser from '@typescript-eslint/parser'
import reactHooks from 'eslint-plugin-react-hooks'
import globals from 'globals'

export default [
  {
    ignores: [
      'dist',
      'node_modules',
      'public/mockServiceWorker.js',
      '*.config.js',
      '*.config.ts',
    ],
  },
  js.configs.recommended,
  {
    files: ['src/**/*.{ts,tsx}'],
    languageOptions: {
      parser: tsparser,
      ecmaVersion: 2022,
      sourceType: 'module',
      globals: {
        ...globals.browser,
        ...globals.es2022,
      },
      parserOptions: {
        ecmaFeatures: { jsx: true },
      },
    },
    plugins: {
      '@typescript-eslint': tsplugin,
      'react-hooks': reactHooks,
    },
    rules: {
      ...tsplugin.configs.recommended.rules,
      ...reactHooks.configs.recommended.rules,
      // TS가 처리하는 영역은 ESLint에서 꺼둠
      'no-unused-vars': 'off',
      'no-undef': 'off',
      '@typescript-eslint/no-unused-vars': [
        'warn',
        { argsIgnorePattern: '^_', varsIgnorePattern: '^_' },
      ],
      // 기존 코드에 `as any`/`as 0` 잔존 — 일단 warn
      '@typescript-eslint/no-explicit-any': 'warn',
      // React 18 자동 JSX 변환 환경
      'react-hooks/exhaustive-deps': 'warn',
      'react-hooks/rules-of-hooks': 'error',
    },
  },
]
