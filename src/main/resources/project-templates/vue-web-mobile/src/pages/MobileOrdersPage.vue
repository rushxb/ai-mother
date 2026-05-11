<template>
  <div class="mobile-page">
    <van-nav-bar title="我的订单" />
    <van-tabs v-model:active="activeTab" animated>
      <van-tab title="进行中">
        <section class="mobile-section">
          <article v-for="item in orders" :key="item.id" class="order-card">
            <div class="section-head">
              <strong>{{ item.id }}</strong>
              <van-tag type="primary">{{ orderSteps[item.status] }}</van-tag>
            </div>
            <p>{{ item.title }}</p>
            <van-steps :active="item.status" class="order-steps">
              <van-step v-for="step in orderSteps" :key="step">{{ step }}</van-step>
            </van-steps>
            <div class="order-foot">
              <span>{{ item.eta }}</span>
              <strong>¥{{ item.amount.toFixed(1) }}</strong>
            </div>
          </article>
        </section>
      </van-tab>
      <van-tab title="已完成">
        <van-empty description="这里可改造成历史订单、售后或退款记录。" />
      </van-tab>
    </van-tabs>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { orderSteps, orders } from '@/data/mobileData'

const activeTab = ref(0)
</script>
