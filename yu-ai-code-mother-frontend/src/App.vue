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
  --font-latin: 'Rush Arial', Arial;
  --font-cjk-kai: 'Rush Kai', 'BiauKai', 'DFKai-SB', 'STKaiti', 'KaiTi', 'Kaiti SC', serif;
  --font-ui: var(--font-latin), var(--font-cjk-kai), sans-serif;
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
  background: #f8fafc;
  color: #0f172a;
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
  background: #f8fafc;
}
</style>
