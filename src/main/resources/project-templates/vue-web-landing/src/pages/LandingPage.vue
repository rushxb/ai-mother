<template>
  <div class="landing-shell">
    <header class="landing-header">
      <a class="brand" href="#top">{{ brand.name }}</a>
      <nav>
        <a v-for="item in nav" :key="item" :href="`#${item}`">{{ item }}</a>
      </nav>
    </header>

    <main id="top">
      <section class="landing-hero">
        <div class="hero-copy">
          <span class="eyebrow">Launch Template</span>
          <h1>{{ brand.headline }}</h1>
          <p>{{ brand.description }}</p>
          <div class="hero-actions">
            <a class="btn primary" href="#价格">{{ brand.cta }}</a>
            <a class="btn secondary" href="#案例">{{ brand.secondary }}</a>
          </div>
        </div>
        <div class="hero-visual">
          <div class="visual-card main">
            <span>Conversion Map</span>
            <strong>Hero → Proof → Offer → Action</strong>
          </div>
          <div class="visual-grid">
            <span v-for="item in stats" :key="item.label">
              <strong>{{ item.value }}</strong>
              {{ item.label }}
            </span>
          </div>
        </div>
      </section>

      <LandingSection id="亮点" eyebrow="Highlights" title="默认预置的转化模块" description="每个模块都能被替换成真实行业内容，也能被 AI 拆成独立组件。">
        <div class="highlight-grid">
          <article v-for="item in highlights" :key="item.title" class="card">
            <h3>{{ item.title }}</h3>
            <p>{{ item.text }}</p>
          </article>
        </div>
      </LandingSection>

      <LandingSection id="案例" eyebrow="Cases" title="适合改造的典型场景">
        <div class="case-row">
          <article v-for="item in cases" :key="item.title" class="case-card">
            <span>{{ item.title }}</span>
            <p>{{ item.text }}</p>
          </article>
        </div>
      </LandingSection>

      <LandingSection id="流程" eyebrow="Process" title="从需求到上线的页面节奏">
        <ol class="process-list">
          <li v-for="(item, index) in process" :key="item">
            <span>{{ index + 1 }}</span>
            {{ item }}
          </li>
        </ol>
      </LandingSection>

      <LandingSection id="价格" eyebrow="Pricing" title="套餐区域可改造成报价、权益或报名档位">
        <div class="plan-grid">
          <article v-for="plan in plans" :key="plan.name" class="plan-card">
            <h3>{{ plan.name }}</h3>
            <strong>{{ plan.price }}</strong>
            <p>{{ plan.desc }}</p>
            <ul>
              <li v-for="feature in plan.features" :key="feature">{{ feature }}</li>
            </ul>
            <button type="button">选择方案</button>
          </article>
        </div>
      </LandingSection>

      <LandingSection id="FAQ" eyebrow="FAQ" title="常见问题">
        <div class="faq-list">
          <details v-for="item in faqs" :key="item.q" open>
            <summary>{{ item.q }}</summary>
            <p>{{ item.a }}</p>
          </details>
        </div>
      </LandingSection>
    </main>
  </div>
</template>

<script setup>
import LandingSection from '@/components/LandingSection.vue'
import { brand, cases, faqs, highlights, nav, plans, process, stats } from '@/data/landingData'
</script>
