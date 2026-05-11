<template>
  <MetricGrid :items="metrics" />
  <section class="dashboard-grid">
    <article id="trend" class="panel wide">
      <div class="panel-head">
        <h2>业务趋势</h2>
        <span>近 7 日</span>
      </div>
      <div class="bars" aria-label="趋势图">
        <span v-for="height in [42, 68, 54, 88, 74, 96, 82]" :key="height" :style="{ height: height + '%' }" />
      </div>
    </article>
    <article id="orders" class="panel">
      <div class="panel-head">
        <h2>近期动态</h2>
      </div>
      <ul class="activity-list">
        <li v-for="item in activities" :key="item">{{ item }}</li>
      </ul>
    </article>
  </section>

  <section id="overview" class="dashboard-grid">
    <ProTable :columns="columns" :rows="rows" row-key="no">
      <template #status="{ row }">
        <span class="tag">{{ row.status }}</span>
      </template>
    </ProTable>
    <article id="settings" class="panel">
      <h2>预留设置区</h2>
      <p>可以继续扩展系统开关、权限配置、消息通知或外观主题。</p>
    </article>
  </section>
</template>

<script setup>
import MetricGrid from '@/components/MetricGrid.vue'
import ProTable from '@/components/ProTable.vue'
import { activities, metrics, orders } from '@/data/adminData'

const columns = [
  { key: 'no', title: '订单号' },
  { key: 'product', title: '产品' },
  { key: 'buyer', title: '客户' },
  { key: 'status', title: '状态' }
]

const rows = orders

// @AI_INJECT_VIEW
</script>
