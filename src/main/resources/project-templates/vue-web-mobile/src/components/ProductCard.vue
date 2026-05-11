<template>
  <article class="product-card">
    <div class="product-media">{{ item.name.slice(0, 1) }}</div>
    <div class="product-body">
      <strong>{{ item.name }}</strong>
      <span>{{ item.tag }}</span>
      <div class="product-row">
        <em>¥{{ item.price.toFixed(1) }}</em>
        <van-button size="small" round type="primary">加入</van-button>
      </div>
    </div>
  </article>
</template>

<script setup>
defineProps({
  item: { type: Object, required: true }
})
</script>
