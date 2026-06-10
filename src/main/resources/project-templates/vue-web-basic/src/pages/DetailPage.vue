<template>
  <section class="page-hero compact">
    <span class="eyebrow">Detail</span>
    <h1>{{ current.title }}</h1>
    <p>{{ current.desc }}</p>
  </section>
  <section class="detail-layout">
    <article class="article-panel">
      <h2>模块说明</h2>
      <p>
        这是一个预留详情页，适合改造成产品详情、服务详情、文章页、案例页或任务详情。
        AI 可以基于路由参数替换当前内容，也可以把这里拆成多个业务组件。
      </p>
      <ul>
        <li>保留页面标题、正文、侧边摘要和行动按钮的稳定结构。</li>
        <li>需要接入真实接口时，优先新增 api 适配层，不把请求散落在多个组件里。</li>
        <li>需要更多详情区块时，在 article 内追加 section 即可。</li>
      </ul>
    </article>
    <aside class="side-panel">
      <strong>当前模块</strong>
      <span>{{ current.id }}</span>
      <RouterLink class="btn primary full" to="/">返回首页</RouterLink>
    </aside>
  </section>
</template>

<script setup>
import { computed } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { cards } from '@/data/siteData'

const route = useRoute()
const current = computed(() => cards.find((item) => item.id === route.params.id) || cards[0])
</script>
