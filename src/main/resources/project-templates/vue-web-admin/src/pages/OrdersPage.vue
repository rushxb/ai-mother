<template>
  <section class="panel">
    <div class="panel-head">
      <h2>订单列表</h2>
      <button class="primary" type="button">创建订单</button>
    </div>
    <DataTable :columns="columns" :rows="orders" row-key="no">
      <template #total="{ row }">¥{{ row.total.toLocaleString() }}</template>
      <template #status="{ row }">
        <span class="tag">{{ row.status }}</span>
      </template>
    </DataTable>
  </section>
</template>

<script setup>
import DataTable from '@/components/DataTable.vue'
import { orders } from '@/data/adminData'

const columns = [
  { title: '订单号', key: 'no' },
  { title: '产品', key: 'product' },
  { title: '客户', key: 'buyer' },
  { title: '状态', key: 'status' },
  { title: '金额', key: 'total' }
]
</script>
