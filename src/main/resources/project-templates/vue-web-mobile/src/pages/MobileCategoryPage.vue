<template>
  <div class="mobile-page">
    <van-nav-bar title="商品分类" />
    <div class="category-layout">
      <aside class="category-menu">
        <button
          v-for="item in categories"
          :key="item.title"
          type="button"
          :class="{ active: activeCategory === item.title }"
          @click="activeCategory = item.title"
        >
          <strong>{{ item.title }}</strong>
          <span>{{ item.count }}</span>
        </button>
      </aside>
      <section class="category-panel">
        <div class="section-head">
          <h2>{{ activeCategory }}</h2>
          <span>可快速改造成二级类目、筛选或专题页</span>
        </div>
        <div class="product-list">
          <ProductCard v-for="item in demoItems" :key="item.id" :item="item" />
        </div>
      </section>
    </div>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import ProductCard from '@/components/ProductCard.vue'
import { categories, productSections } from '@/data/mobileData'

const activeCategory = ref(categories[0].title)
const demoItems = computed(() => productSections.flatMap((item) => item.items))
</script>
