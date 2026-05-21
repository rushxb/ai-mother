<template>
  <div class="dashboard-view">
    <!-- Metrics Grid -->
    <n-grid :cols="4" :x-gap="16" :y-gap="16">
      <n-gi v-for="item in metrics" :key="item.label">
        <n-card>
          <n-statistic :label="item.label" :value="item.value">
            <template #suffix>
              <n-text :type="item.trendType === 'up' ? 'success' : 'error'">
                {{ item.trend }}
              </n-text>
            </template>
          </n-statistic>
        </n-card>
      </n-gi>
    </n-grid>

    <!-- Charts and Activity -->
    <n-grid :cols="2" :x-gap="16" :y-gap="16" style="margin-top: 16px">
      <n-gi>
        <n-card title="业务趋势">
          <template #header-extra>
            <n-text depth="3">近 7 日</n-text>
          </template>
          <div class="bars" aria-label="趋势图">
            <span
              v-for="(height, index) in trendData"
              :key="index"
              :style="{ height: height + '%' }"
            />
          </div>
        </n-card>
      </n-gi>
      
      <n-gi>
        <n-card title="近期动态">
          <n-list hoverable>
            <n-list-item v-for="(item, index) in activities" :key="index">
              <n-thing :title="item" />
            </n-list-item>
          </n-list>
        </n-card>
      </n-gi>
    </n-grid>

    <!-- Orders Table -->
    <n-card title="订单列表" style="margin-top: 16px">
      <template #header-extra>
        <n-button text type="primary" @click="router.push('/orders')">
          查看全部
        </n-button>
      </template>
      
      <n-data-table
        :columns="columns"
        :data="orders"
        :bordered="false"
        :single-line="false"
      />
    </n-card>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import type { DataTableColumns } from 'naive-ui'
import { NTag } from 'naive-ui'
import { metrics, orders, activities } from '@/data/adminData'
import type { OrderInfo } from '@/types'

const router = useRouter()

const trendData = ref([42, 68, 54, 88, 74, 96, 82])

const columns: DataTableColumns<OrderInfo> = [
  { title: '订单号', key: 'no', width: 150 },
  { title: '产品', key: 'product', width: 150 },
  { title: '客户', key: 'buyer', width: 100 },
  {
    title: '状态',
    key: 'status',
    width: 100,
    render(row) {
      const typeMap: Record<string, 'success' | 'warning' | 'error' | 'info'> = {
        '已完成': 'success',
        '待支付': 'warning',
        '已取消': 'error',
        '进行中': 'info'
      }
      return h(NTag, { type: typeMap[row.status] || 'info', size: 'small' }, { default: () => row.status })
    }
  },
  { title: '金额', key: 'amount', width: 100 },
  { title: '创建时间', key: 'createTime', width: 180 }
]

// @AI_INJECT_VIEW
</script>

<style scoped>
.dashboard-view {
  padding: 16px;
}

.bars {
  display: flex;
  align-items: flex-end;
  gap: 8px;
  height: 200px;
  padding: 16px 0;
}

.bars span {
  flex: 1;
  background: linear-gradient(to top, var(--primary), var(--primary-light));
  border-radius: 4px 4px 0 0;
  transition: height 0.3s ease;
}
</style>
