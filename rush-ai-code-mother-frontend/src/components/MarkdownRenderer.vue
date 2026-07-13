<template>
  <article class="markdown-content" v-html="renderedMarkdown"></article>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import MarkdownIt from 'markdown-it'
import hljs from '@/utils/codeHighlighter'

interface Props {
  content: string
}

const props = defineProps<Props>()

const md: MarkdownIt = new MarkdownIt({
  html: false,
  linkify: true,
  typographer: true,
  breaks: true,
  highlight: function (str: string, lang: string): string {
    if (lang && hljs.getLanguage(lang)) {
      try {
        return (
          `<pre class="hljs" data-language="${md.utils.escapeHtml(lang.toUpperCase())}"><code>` +
          hljs.highlight(str, { language: lang, ignoreIllegals: true }).value +
          '</code></pre>'
        )
      } catch {
        // 忽略错误，使用默认处理
      }
    }

    return '<pre class="hljs"><code>' + md.utils.escapeHtml(str) + '</code></pre>'
  },
})

// Open generated links in an isolated browsing context. markdown-it still
// performs its own protocol validation before this renderer is reached.
const defaultLinkOpen =
  md.renderer.rules.link_open ??
  ((tokens, index, options, _environment, self) => self.renderToken(tokens, index, options))

md.renderer.rules.link_open = (tokens, index, options, environment, self) => {
  tokens[index]?.attrSet('target', '_blank')
  tokens[index]?.attrSet('rel', 'noopener noreferrer')
  return defaultLinkOpen(tokens, index, options, environment, self)
}

const renderedMarkdown = computed(() => {
  return md.render(props.content || '')
})
</script>

<style scoped>
.markdown-content {
  --markdown-ink-strong: var(--chat-ink-strong, var(--color-ink-strong, #102033));
  --markdown-ink: var(--chat-ink, var(--color-ink, #2f4158));
  --markdown-muted: var(--chat-ink-soft, var(--color-ink-soft, #6f8198));
  --markdown-primary: var(--chat-primary, var(--color-primary, #2f8bff));
  --markdown-line: var(--chat-line, var(--color-line, rgba(112, 140, 175, 0.18)));
  min-width: 0;
  color: var(--markdown-ink);
  font-size: 14px;
  line-height: 1.75;
  overflow-wrap: anywhere;
}

.markdown-content :deep(*:first-child) {
  margin-top: 0;
}

.markdown-content :deep(*:last-child) {
  margin-bottom: 0;
}

.markdown-content :deep(h1),
.markdown-content :deep(h2),
.markdown-content :deep(h3),
.markdown-content :deep(h4),
.markdown-content :deep(h5),
.markdown-content :deep(h6) {
  margin: 1.65em 0 0.55em;
  color: var(--markdown-ink-strong);
  font-weight: 760;
  letter-spacing: -0.025em;
  line-height: 1.28;
  scroll-margin-top: 80px;
}

.markdown-content :deep(h1) {
  padding-bottom: 0.42em;
  border-bottom: 1px solid var(--markdown-line);
  font-size: 1.55em;
}

.markdown-content :deep(h2) {
  padding-bottom: 0.38em;
  border-bottom: 1px solid var(--markdown-line);
  font-size: 1.34em;
}

.markdown-content :deep(h3) {
  font-size: 1.16em;
}

.markdown-content :deep(h4),
.markdown-content :deep(h5),
.markdown-content :deep(h6) {
  font-size: 1em;
}

.markdown-content :deep(p) {
  margin: 0.85em 0;
}

.markdown-content :deep(ul),
.markdown-content :deep(ol) {
  margin: 0.85em 0;
  padding-left: 1.45em;
}

.markdown-content :deep(li) {
  margin: 0.28em 0;
  padding-left: 0.15em;
}

.markdown-content :deep(li::marker) {
  color: var(--markdown-primary);
  font-weight: 700;
}

.markdown-content :deep(li > ul),
.markdown-content :deep(li > ol) {
  margin-top: 0.3em;
  margin-bottom: 0.3em;
}

.markdown-content :deep(blockquote) {
  position: relative;
  margin: 1.1em 0;
  padding: 0.8em 1em 0.8em 1.1em;
  border: 1px solid rgba(47, 139, 255, 0.12);
  border-left: 3px solid rgba(47, 139, 255, 0.52);
  border-radius: 4px 12px 12px 4px;
  background: rgba(47, 139, 255, 0.055);
  color: var(--markdown-ink);
}

.markdown-content :deep(blockquote p) {
  margin: 0.25em 0;
}

.markdown-content :deep(code) {
  padding: 0.18em 0.42em;
  border: 1px solid rgba(112, 140, 175, 0.14);
  border-radius: 6px;
  background: rgba(112, 140, 175, 0.1);
  color: #176fdd;
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', monospace;
  font-size: 0.88em;
}

.markdown-content :deep(pre) {
  position: relative;
  overflow-x: auto;
  margin: 1.15em 0;
  padding: 18px;
  border: 1px solid rgba(116, 150, 192, 0.18);
  border-radius: 14px;
  background:
    radial-gradient(circle at 100% 0%, rgba(47, 139, 255, 0.1), transparent 34%),
    #101b2a;
  box-shadow: 0 14px 28px rgba(20, 39, 62, 0.14);
  scrollbar-color: rgba(143, 172, 207, 0.36) transparent;
  scrollbar-width: thin;
}

.markdown-content :deep(pre[data-language]::before) {
  content: attr(data-language);
  position: sticky;
  left: calc(100% - 42px);
  display: block;
  width: max-content;
  margin: -6px 0 8px auto;
  color: rgba(182, 203, 227, 0.5);
  font-family: var(--font-ui);
  font-size: 9px;
  font-weight: 800;
  letter-spacing: 0.12em;
}

.markdown-content :deep(pre code) {
  padding: 0;
  border: 0;
  border-radius: 0;
  background: transparent;
  color: #d8e5f3;
  font-size: 0.86em;
  line-height: 1.65;
}

.markdown-content :deep(table) {
  display: block;
  overflow-x: auto;
  border-collapse: collapse;
  margin: 1.1em 0;
  width: 100%;
  border: 1px solid var(--markdown-line);
  border-radius: 12px;
  scrollbar-width: thin;
}

.markdown-content :deep(table th),
.markdown-content :deep(table td) {
  min-width: 120px;
  padding: 0.68em 0.85em;
  border-right: 1px solid var(--markdown-line);
  border-bottom: 1px solid var(--markdown-line);
  text-align: left;
}

.markdown-content :deep(table th) {
  background: rgba(112, 140, 175, 0.08);
  color: var(--markdown-ink-strong);
  font-size: 12px;
  font-weight: 750;
}

.markdown-content :deep(table tr:nth-child(even)) {
  background: rgba(246, 249, 253, 0.6);
}

.markdown-content :deep(a) {
  color: var(--markdown-primary);
  font-weight: 600;
  text-decoration: none;
  text-decoration-thickness: 1px;
  text-underline-offset: 3px;
}

.markdown-content :deep(a:hover) {
  text-decoration: underline;
}

.markdown-content :deep(img) {
  display: block;
  max-width: 100%;
  height: auto;
  margin: 1em auto;
  border: 1px solid var(--markdown-line);
  border-radius: 14px;
  box-shadow: 0 14px 34px rgba(68, 96, 136, 0.12);
}

.markdown-content :deep(hr) {
  margin: 1.6em 0;
  border: 0;
  border-top: 1px solid var(--markdown-line);
}

.markdown-content :deep(.hljs) {
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', monospace;
}

.markdown-content :deep(.hljs-keyword) {
  color: #ff8fb3;
  font-weight: 650;
}

.markdown-content :deep(.hljs-string) {
  color: #9ee6c3;
}

.markdown-content :deep(.hljs-comment) {
  color: #7890aa;
  font-style: italic;
}

.markdown-content :deep(.hljs-number) {
  color: #82c9ff;
}

.markdown-content :deep(.hljs-title),
.markdown-content :deep(.hljs-function),
.markdown-content :deep(.hljs-title.function_) {
  color: #b8a6ff;
  font-weight: 650;
}

.markdown-content :deep(.hljs-tag) {
  color: #7ad8ef;
}

.markdown-content :deep(.hljs-attr) {
  color: #ffd68a;
}

.markdown-content :deep(.hljs-literal),
.markdown-content :deep(.hljs-built_in),
.markdown-content :deep(.hljs-type) {
  color: #ffb983;
}
</style>
