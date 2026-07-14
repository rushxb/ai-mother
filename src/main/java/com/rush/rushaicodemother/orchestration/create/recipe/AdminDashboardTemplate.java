package com.rush.rushaicodemother.orchestration.create.recipe;

import org.springframework.stereotype.Component;

import static com.rush.rushaicodemother.orchestration.create.recipe.RecipeValueSupport.escape;

/** Renders the admin dashboard view. */
@Component
final class AdminDashboardTemplate {

    String adminDashboardView(AdminRecipe recipe) {
        return """
                <template>
                  <div :class="['dashboard-view', dashboardClass]">
                    <div class="dashboard-toolbar">
                      <div>
                        <p class="text-sm text-muted-foreground">%s</p>
                        <h1 class="text-2xl font-semibold">%s工作台</h1>
                      </div>
                      <div class="toolbar-actions">
                        <Button v-if="enabledInteractions.includes('batch')" variant="outline">批量处理</Button>
                        <Button v-if="enabledInteractions.includes('export')" variant="outline">导出</Button>
                        <Button>新增%s</Button>
                      </div>
                    </div>

                    <div v-if="enabledInteractions.includes('filter')" class="filter-strip">
                      <span v-for="item in filterChips" :key="item" class="filter-chip">{{ item }}</span>
                    </div>

                    <div class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 metric-grid">
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

                    <div class="grid grid-cols-1 lg:grid-cols-2 gap-4">
                      <Card>
                        <CardHeader class="flex flex-row items-center justify-between">
                          <CardTitle>{{ primaryViz.title }}</CardTitle>
                          <span class="text-sm text-muted-foreground">{{ primaryViz.range }}</span>
                        </CardHeader>
                        <CardContent>
                          <div class="flex items-end gap-2 h-[200px] p-4">
                            <div
                              v-for="(height, index) in trendData"
                              :key="index"
                              class="flex-1 rounded-t-md bg-gradient-to-t from-primary/80 to-primary/40 transition-all duration-300"
                              :style="{ height: height + '%%' }"
                            />
                          </div>
                        </CardContent>
                      </Card>

                      <Card>
                        <CardHeader>
                          <CardTitle>{{ secondaryViz.title }}</CardTitle>
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

                    <Card>
                      <CardHeader class="flex flex-row items-center justify-between">
                        <CardTitle>%s列表</CardTitle>
                        <Button variant="link" @click="router.push('/orders')">查看全部</Button>
                      </CardHeader>
                      <CardContent>
                        <div class="rounded-md border">
                          <table class="w-full caption-bottom text-sm">
                            <thead class="[&_tr]:border-b">
                              <tr class="border-b transition-colors hover:bg-muted/50">
                                <th v-for="column in columns" :key="column.key" class="h-12 px-4 text-left align-middle font-medium text-muted-foreground">
                                  {{ column.title }}
                                </th>
                              </tr>
                            </thead>
                            <tbody class="[&_tr:last-child]:border-0">
                              <tr v-for="row in orders" :key="row.no" class="border-b transition-colors hover:bg-muted/50">
                                <td class="p-4 align-middle font-medium">{{ row.no }}</td>
                                <td v-for="column in valueColumns" :key="column.key" class="p-4 align-middle">
                                  {{ row[column.key] ?? '-' }}
                                </td>
                                <td v-if="hasStatusColumn" class="p-4 align-middle">
                                  <Badge :variant="statusVariant(row.status)">{{ row.status }}</Badge>
                                </td>
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
                import { activities, dashboardClass, enabledInteractions, filterChips, metrics, orders, visualizationBlocks } from '@/data/adminData'
                import { columns } from '@/data/table.columns'

                const router = useRouter()
                const primaryViz = visualizationBlocks[0]
                const secondaryViz = visualizationBlocks[1] ?? visualizationBlocks[0]
                const trendData = ref(primaryViz.values)
                const valueColumns = columns.filter(item => item.key !== 'no' && item.key !== 'status')
                const hasStatusColumn = columns.some(item => item.key === 'status')

                function statusVariant(status: string): 'default' | 'secondary' | 'destructive' | 'outline' {
                  const map: Record<string, 'default' | 'secondary' | 'destructive' | 'outline'> = {
                    '已完成': 'default',
                    '待处理': 'secondary',
                    '已取消': 'destructive',
                    '进行中': 'outline'
                  }
                  return map[status] || 'outline'
                }
                </script>

                <style scoped>
                .dashboard-view {
                  padding: var(--dashboard-padding);
                  display: flex;
                  flex-direction: column;
                  gap: var(--dashboard-gap);
                }
                .dashboard-toolbar {
                  display: flex;
                  align-items: center;
                  justify-content: space-between;
                  gap: 16px;
                }
                .toolbar-actions {
                  display: flex;
                  gap: 8px;
                  flex-wrap: wrap;
                }
                .metric-grid {
                  gap: var(--dashboard-gap);
                }
                .filter-strip {
                  display: flex;
                  flex-wrap: wrap;
                  gap: 8px;
                }
                .filter-chip {
                  border: 1px solid hsl(var(--border));
                  border-radius: var(--panel-radius);
                  padding: var(--chip-padding);
                  background: var(--surface-muted);
                  font-size: var(--table-font-size);
                }
                </style>
                """.formatted(
                escape(recipe.domain()),
                escape(recipe.brand()),
                escape(recipe.entityLabel()),
                escape(recipe.entityLabel())
        );
    }
}
