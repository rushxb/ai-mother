<template>
  <section class="page-hero compact">
    <span class="eyebrow">About</span>
    <h1>一个便于继续生成的通用页面</h1>
    <p>这里可以改造成关于我们、团队介绍、联系页、帮助中心或品牌说明页。</p>
  </section>
  <section class="content-band">
    <div class="info-list">
      <div v-for="item in features" :key="item.title">
        <strong>{{ item.title }}</strong>
        <p>{{ item.text }}</p>
      </div>
    </div>
  </section>
</template>

<script setup>
import { features } from '@/data/siteData'
</script>
