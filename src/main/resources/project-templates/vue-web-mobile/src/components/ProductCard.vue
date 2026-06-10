<template>
  <article class="product-card">
    <div class="product-card__body">
      <div class="product-card__meta">
        <span v-if="item.tag" class="product-card__tag">{{ item.tag }}</span>
        <span v-if="item.originalPrice" class="product-card__original">¥{{ formatPrice(item.originalPrice) }}</span>
      </div>
      <h3 class="product-card__title">{{ item.name }}</h3>
      <p v-if="item.description" class="product-card__desc">{{ item.description }}</p>
      <div class="product-card__footer">
        <div class="product-card__price">
          <span class="product-card__currency">¥</span>
          <strong>{{ formatPrice(item.price) }}</strong>
        </div>
        <button type="button" class="product-card__action">加入购物车</button>
      </div>
    </div>
  </article>
</template>

<script setup>
defineProps({
  item: {
    type: Object,
    required: true
  }
})

function formatPrice(price) {
  return Number(price || 0).toFixed(Number.isInteger(Number(price || 0)) ? 0 : 1)
}
</script>

<style scoped>
.product-card {
  background: #ffffff;
  border: 1px solid rgba(239, 90, 47, 0.08);
  border-radius: 14px;
  box-shadow: 0 6px 18px rgba(15, 23, 42, 0.05);
  overflow: hidden;
}

.product-card__body {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 14px;
}

.product-card__meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.product-card__tag {
  display: inline-flex;
  align-items: center;
  padding: 4px 8px;
  border-radius: 999px;
  background: rgba(239, 90, 47, 0.12);
  color: #ef5a2f;
  font-size: 11px;
  font-weight: 600;
}

.product-card__original {
  color: #94a3b8;
  font-size: 12px;
  text-decoration: line-through;
}

.product-card__title {
  margin: 0;
  color: #1f1a17;
  font-size: 15px;
  font-weight: 700;
  line-height: 1.4;
}

.product-card__desc {
  margin: 0;
  color: #64748b;
  font-size: 12px;
  line-height: 1.6;
}

.product-card__footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.product-card__price {
  display: flex;
  align-items: baseline;
  color: #ef5a2f;
}

.product-card__currency {
  font-size: 12px;
  margin-right: 2px;
}

.product-card__price strong {
  font-size: 20px;
  line-height: 1;
}

.product-card__action {
  border: none;
  border-radius: 999px;
  background: #ef5a2f;
  color: #fff;
  font-size: 12px;
  font-weight: 600;
  padding: 8px 12px;
}
</style>
