<template>
  <section id="hero" class="hero">
    <div class="hero-copy">
      <span class="eyebrow">{{ hero.eyebrow }}</span>
      <h1>{{ hero.title }}</h1>
      <p>{{ hero.description }}</p>
      <div class="hero-actions">
        <a
          v-for="action in hero.actions"
          :key="action.label"
          :class="['btn', action.type]"
          :href="action.path"
        >
          {{ action.label }}
        </a>
      </div>
    </div>
    <div class="hero-panel">
      <div v-for="item in features" :key="item.title" class="metric-card">
        <strong>{{ item.stat }}</strong>
        <span>{{ item.title }}</span>
        <p>{{ item.text }}</p>
      </div>
    </div>
  </section>

  <section id="modules" class="content-band">
    <SectionTitle
      eyebrow="Modules"
      title="可替换的业务模块"
      description="这些模块用于给 AI 提供明确落点，可按需求增删或改造成真实业务。"
    />
    <div class="card-grid">
      <article v-for="card in cards" :key="card.id" class="feature-card">
        <span>{{ card.id }}</span>
        <h3>{{ card.title }}</h3>
        <p>{{ card.desc }}</p>
      </article>
    </div>
  </section>

  <section id="timeline" class="split-section">
    <div>
      <SectionTitle title="默认交付流程" description="流程区可以改造成服务流程、项目阶段、使用步骤或产品路线图。" />
    </div>
    <ol class="timeline">
      <li v-for="(item, index) in timeline" :key="item">
        <span>{{ String(index + 1).padStart(2, '0') }}</span>
        {{ item }}
      </li>
    </ol>
  </section>

  <section id="contact" class="content-band contact-band">
    <SectionTitle
      eyebrow="Contact"
      title="预留咨询区"
      description="适合改造成表单、联系方式、预约入口、客服信息或转化行动区。"
    />
    <div class="contact-grid">
      <article class="contact-card">
        <strong>默认入口</strong>
        <p>把这里替换成真实联系方式、表单或者业务入口即可。</p>
      </article>
      <article class="contact-card">
        <strong>可扩展内容</strong>
        <p>也可以继续增加 FAQ、合作伙伴、案例摘要或下载资源。</p>
      </article>
    </div>
  </section>
</template>

<script setup>
import SectionTitle from '@/components/SectionTitle.vue'
import { cards, features, hero, timeline } from '@/data/siteData'
</script>
