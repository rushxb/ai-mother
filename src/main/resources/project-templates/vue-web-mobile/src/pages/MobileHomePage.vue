<template>
  <div class="mobile-page">
    <van-nav-bar title="轻享生活" />
    <van-search v-model="keyword" placeholder="搜索商品、门店或活动" />
    <van-notice-bar left-icon="volume-o" text="新用户首单最高减 18 元，晚间时段可用配送券已发放。" />

    <van-swipe class="hero-swipe" :autoplay="3500" indicator-color="#ef5a2f">
      <van-swipe-item v-for="item in banners" :key="item.title">
        <div class="hero-slide">
          <strong>{{ item.title }}</strong>
          <p>{{ item.desc }}</p>
        </div>
      </van-swipe-item>
    </van-swipe>

    <van-grid :column-num="4" :border="false" class="entry-grid">
      <van-grid-item v-for="item in quickEntries" :key="item.title" :text="item.title">
        <template #icon>
          <span class="entry-icon">{{ item.icon }}</span>
        </template>
      </van-grid-item>
    </van-grid>

    <section v-for="section in productSections" :key="section.title" class="mobile-section">
      <div class="section-head">
        <h2>{{ section.title }}</h2>
        <span>更多</span>
      </div>
      <div class="product-list">
        <ProductCard v-for="item in section.items" :key="item.id" :item="item" />
      </div>
    </section>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import ProductCard from '@/components/ProductCard.vue'
import { banners, productSections, quickEntries } from '@/data/mobileData'

const keyword = ref('')
</script>
