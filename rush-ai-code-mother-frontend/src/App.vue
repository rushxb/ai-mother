<script setup lang="ts">
import BasicLayout from '@/layouts/BasicLayout.vue'
</script>

<template>
  <BasicLayout />
</template>

<style>
@font-face {
  font-family: 'Rush Arial';
  src: url('./assets/font/Arial.ttf') format('truetype');
  font-weight: 400 700;
  font-style: normal;
  font-display: swap;
  unicode-range:
    U+0000-00FF,
    U+0100-024F,
    U+1E00-1EFF;
}

@font-face {
  font-family: 'Rush Kai';
  src: url('./assets/font/仓耳今楷W04 .ttf') format('truetype');
  font-weight: 400 700;
  font-style: normal;
  font-display: swap;
  unicode-range:
    U+2E80-2EFF,
    U+3000-303F,
    U+31C0-31EF,
    U+3400-4DBF,
    U+4E00-9FFF,
    U+F900-FAFF,
    U+FF00-FFEF;
}

:root {
  color-scheme: light;
  --font-latin: 'Rush Arial', 'Aptos', 'Segoe UI Variable', sans-serif;
  --font-cjk-kai: 'Rush Kai', 'STKaiti', 'KaiTi', serif;
  --font-ui: var(--font-latin), 'PingFang SC', 'HarmonyOS Sans SC', 'Microsoft YaHei', sans-serif;
  --font-display: var(--font-latin), var(--font-cjk-kai), sans-serif;

  --color-ink-strong: #102033;
  --color-ink: #2f4158;
  --color-ink-soft: #6f8198;
  --color-surface: #ffffff;
  --color-surface-soft: #f6f9fd;
  --color-line: rgba(112, 140, 175, 0.18);
  --color-primary: #2f8bff;
  --color-primary-strong: #176fdd;
  --color-secondary: #3cc9bb;
  --color-danger: #e54868;

  --radius-sm: 10px;
  --radius-md: 16px;
  --radius-lg: 24px;
  --radius-xl: 32px;
  --shadow-soft: 0 18px 52px rgba(73, 103, 143, 0.1);
  --shadow-panel: 0 26px 78px rgba(68, 96, 136, 0.14);
  --ease-out: cubic-bezier(0.22, 1, 0.36, 1);
  --page-max-width: 1480px;
}

/* 全局样式优化 */
* {
  box-sizing: border-box;
}

html {
  height: 100%;
  overflow-x: hidden;
  font-family: var(--font-ui);
}

body {
  margin: 0;
  padding: 0;
  background: var(--color-surface-soft);
  color: var(--color-ink-strong);
  font-family: var(--font-ui);
  -webkit-font-smoothing: antialiased;
  -moz-osx-font-smoothing: grayscale;
  overflow-x: hidden;
}

body,
button,
input,
select,
textarea {
  font-family: var(--font-ui) !important;
}

#app,
.ant-app,
.ant-layout,
.ant-layout-header,
.ant-layout-content,
.ant-layout-footer,
.ant-typography,
.ant-btn,
.ant-input,
.ant-input-affix-wrapper,
.ant-select,
.ant-select-selector,
.ant-menu,
.ant-card,
.ant-modal,
.ant-table,
.ant-form,
.ant-tabs,
.ant-dropdown,
.ant-pagination,
.ant-descriptions,
.ant-statistic,
.ant-empty-description {
  font-family: var(--font-ui) !important;
}

body,
#app {
  min-height: 100%;
  background: var(--color-surface-soft);
}

::selection {
  background: rgba(47, 139, 255, 0.2);
  color: var(--color-ink-strong);
}

button,
a,
input,
textarea,
select {
  -webkit-tap-highlight-color: transparent;
}

:focus-visible {
  outline: 2px solid rgba(47, 139, 255, 0.62);
  outline-offset: 3px;
}

@media (prefers-reduced-motion: reduce) {
  *,
  *::before,
  *::after {
    scroll-behavior: auto !important;
    animation-duration: 0.01ms !important;
    animation-iteration-count: 1 !important;
    transition-duration: 0.01ms !important;
  }
}
</style>
