<template>
  <div class="dashboard-view space-y-4">
    <!-- Metrics Grid -->
    <div class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4">
      <Card v-for="item in metrics" :key="item.label">
        <CardHeader class="flex flex-row items-center justify-between space-y-0 pb-2">
          <CardTitle class="text-sm font-medium">{{ item.label }}</CardTitle>
        </CardHeader>
        <CardContent>
          <div class="text-2xl font-bold">{{ item.value }}</div>
          <p class="text-xs" :class="item.trendType === 'up' ? 'text-green-500' : 'text-red-500'">
            {{ item.trend }}
          </p>
        </CardContent>
      </Card>
    </div>

    <!-- Charts and Activity -->
    <div class="grid grid-cols-1 lg:grid-cols-2 gap-4">
      <Card>
        <CardHeader class="flex flex-row items-center justify-between">
          <CardTitle>业务趋势</CardTitle>
          <span class="text-sm text-muted-foreground">近 7 日</span>
        </CardHeader>
        <CardContent>
          <div class="flex items-end gap-2 h-[200px] p-4">
            <div
              v-for="(height, index) in trendData"
              :key="index"
              class="flex-1 rounded-t-md bg-gradient-to-t from-primary/80 to-primary/40 transition-all duration-300"
              :style="{ height: height + '%' }"
            />
          </div>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>近期动态</CardTitle>
        </CardHeader>
        <CardContent>
          <div class="space-y-3">
            <div v-for="(item, index) in activities" :key="index" class="flex items-center gap-3 p-2 rounded-md hover:bg-muted/50 transition-colors">
              <div class="w-2 h-2 rounded-full bg-primary" />
              <span class="text-sm">{{ item }}</span>
            </div>
          </div>
        </CardContent>
      </Card>
    </div>

    <!-- Orders Table -->
    <Card>
      <CardHeader class="flex flex-row items-center justify-between">
        <CardTitle>订单列表</CardTitle>
        <Button variant="link" @click="router.push('/orders')">查看全部</Button>
      </CardHeader>
      <CardContent>
        <div class="rounded-md border">
          <table class="w-full caption-bottom text-sm">
            <thead class="[&_tr]:border-b">
              <tr class="border-b transition-colors hover:bg-muted/50">
                <th class="h-12 px-4 text-left align-middle font-medium text-muted-foreground">订单号</th>
                <th class="h-12 px-4 text-left align-middle font-medium text-muted-foreground">产品</th>
                <th class="h-12 px-4 text-left align-middle font-medium text-muted-foreground">客户</th>
                <th class="h-12 px-4 text-left align-middle font-medium text-muted-foreground">状态</th>
                <th class="h-12 px-4 text-left align-middle font-medium text-muted-foreground">金额</th>
                <th class="h-12 px-4 text-left align-middle font-medium text-muted-foreground">创建时间</th>
              </tr>
            </thead>
            <tbody class="[&_tr:last-child]:border-0">
              <tr v-for="row in orders" :key="row.no" class="border-b transition-colors hover:bg-muted/50">
                <td class="p-4 align-middle font-medium">{{ row.no }}</td>
                <td class="p-4 align-middle">{{ row.product }}</td>
                <td class="p-4 align-middle">{{ row.buyer }}</td>
                <td class="p-4 align-middle">
                  <Badge :variant="statusVariant(row.status)">{{ row.status }}</Badge>
                </td>
                <td class="p-4 align-middle">{{ row.amount }}</td>
                <td class="p-4 align-middle">{{ row.createTime }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </CardContent>
    </Card>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { metrics, orders, activities } from '@/data/adminData'

const router = useRouter()

const trendData = ref([42, 68, 54, 88, 74, 96, 82])

function statusVariant(status: string): 'default' | 'secondary' | 'destructive' | 'outline' {
  const map: Record<string, 'default' | 'secondary' | 'destructive' | 'outline'> = {
    '已完成': 'default',
    '待支付': 'secondary',
    '已取消': 'destructive',
    '进行中': 'outline'
  }
  return map[status] || 'outline'
}

// @AI_INJECT_VIEW
</script>

<style scoped>
.dashboard-view {
  padding: 16px;
}
</style>
