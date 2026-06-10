<template>
  <MetricGrid :items="metrics" />
  <section class="dashboard-grid">
    <article class="panel wide">
      <div class="panel-head">
        <h2>业务趋势</h2>
        <span>近 7 日</span>
      </div>
      <div class="bars" aria-label="趋势图">
        <span v-for="height in [42, 68, 54, 88, 74, 96, 82]" :key="height" :style="{ height: height + '%' }" />
      </div>
    </article>
    <article class="panel">
      <div class="panel-head">
        <h2>近期动态</h2>
      </div>
      <ul class="activity-list">
        <li v-for="item in activities" :key="item">{{ item }}</li>
      </ul>
    </article>
  </section>
</template>

<script setup>
import MetricGrid from '@/components/MetricGrid.vue'
import { activities, metrics } from '@/data/adminData'
</script>
