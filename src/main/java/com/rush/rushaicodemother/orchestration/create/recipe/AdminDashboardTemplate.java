package com.rush.rushaicodemother.orchestration.create.recipe;

import org.springframework.stereotype.Component;

import static com.rush.rushaicodemother.orchestration.create.recipe.RecipeValueSupport.escape;

/**
 * 呈现可直接运行的管理工作台。
 *
 * <p>搜索、筛选、分页、表单和批量操作共享同一份响应式记录状态。将这些行为集中在一个
 * 页面模板中，可以避免多个 slot 分别覆盖同一路径，也使 renderer 能用真实产物证明能力。</p>
 */
@Component
final class AdminDashboardTemplate {

    private final AdminDashboardPersistenceTemplate persistenceTemplate;

    AdminDashboardTemplate(AdminDashboardPersistenceTemplate persistenceTemplate) {
        this.persistenceTemplate = persistenceTemplate;
    }

    /** 返回包含完整本地 CRUD 交互的管理端 Dashboard 视图。 */
    String adminDashboardView(AdminRecipe recipe) {
        AdminDashboardPersistenceTemplate.PersistenceView persistence = persistenceTemplate.render(recipe);
        return """
                <template>
                  <div :class="['dashboard-view', dashboardClass]">
                    <div class="dashboard-toolbar">
                      <div>
                        <p class="text-sm text-muted-foreground">%s</p>
                        <h1 class="text-2xl font-semibold">%s工作台</h1>
                      </div>
                      <div class="toolbar-actions">
                        <Button variant="outline" @click="exportRows">导出当前结果</Button>
                        <Button :disabled="saving" @click="openCreate">新增{{ entityLabel }}</Button>
                      </div>
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
                              v-for="(height, index) in primaryViz.values"
                              :key="index"
                              class="flex-1 rounded-t-md bg-gradient-to-t from-primary/80 to-primary/40"
                              :style="{ height: height + '%%' }"
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
                            <div v-for="(item, index) in activities" :key="index" class="activity-item">
                              <div class="activity-dot" />
                              <span class="text-sm">{{ item }}</span>
                            </div>
                          </div>
                        </CardContent>
                      </Card>
                    </div>

                    <Card>
                      <CardHeader class="space-y-4">
                        <div class="management-heading">
                          <div>
                            <CardTitle>{{ entityLabel }}列表</CardTitle>
                            <p class="text-sm text-muted-foreground">%s</p>
                          </div>
                          <span class="inventory-summary">库存预警 {{ inventoryWarningCount }} 项</span>
                        </div>

                        <div v-if="persistenceError" class="persistence-alert" role="alert">
                          {{ persistenceError }}
                        </div>

                        <div class="filter-panel">
                          <input
                            v-model="searchQuery"
                            class="filter-input"
                            :placeholder="`搜索${entityLabel}名称、编号或负责人`"
                          />
                          <select v-model="statusFilter" class="filter-select" aria-label="状态筛选">
                            <option value="">全部状态</option>
                            <option v-for="status in statusOptions" :key="status" :value="status">{{ status }}</option>
                          </select>
                          <Button variant="outline" @click="resetFilters">重置筛选</Button>
                        </div>

                        <div class="bulk-toolbar">
                          <span class="text-sm text-muted-foreground">已选择 {{ selectedRecordIds.length }} 项</span>
                          <Button
                            variant="outline"
                            :disabled="saving || selectedRecordIds.length === 0"
                            @click="deleteSelected"
                          >
                            批量删除
                          </Button>
                        </div>
                      </CardHeader>

                      <CardContent>
                        <div class="rounded-md border table-scroll">
                          <table class="management-table">
                            <thead>
                              <tr>
                                <th class="selection-cell">
                                  <input
                                    type="checkbox"
                                    :checked="allVisibleSelected"
                                    aria-label="选择当前页全部记录"
                                    @change="toggleAllVisible"
                                  />
                                </th>
                                <th v-for="column in columns" :key="column.key">{{ column.title }}</th>
                                <th>操作</th>
                              </tr>
                            </thead>
                            <tbody>
                              <tr v-for="row in visibleRows" :key="row.no">
                                <td class="selection-cell">
                                  <input
                                    type="checkbox"
                                    :checked="selectedRecordIds.includes(row.no)"
                                    :aria-label="`选择${row.no}`"
                                    @change="toggleSelected(row.no)"
                                  />
                                </td>
                                <td v-for="column in columns" :key="column.key">
                                  <Badge v-if="column.key === 'status'" :variant="statusVariant(String(row.status))">
                                    {{ row.status }}
                                  </Badge>
                                  <span v-else>{{ displayValue(row[column.key]) }}</span>
                                </td>
                                <td>
                                  <div class="row-actions">
                                    <Button variant="outline" :disabled="saving" @click="openEdit(row)">编辑</Button>
                                    <Button variant="outline" :disabled="saving" @click="deleteRecord(row)">删除</Button>
                                  </div>
                                </td>
                              </tr>
                              <tr v-if="visibleRows.length === 0">
                                <td :colspan="columns.length + 2" class="empty-state">没有符合条件的记录</td>
                              </tr>
                            </tbody>
                          </table>
                        </div>

                        <div class="pagination-bar">
                          <span>共 {{ totalItems }} 条，第 {{ currentPage }} / {{ totalPages }} 页</span>
                          <div class="row-actions">
                            <Button variant="outline" :disabled="currentPage <= 1" @click="currentPage--">上一页</Button>
                            <Button variant="outline" :disabled="currentPage >= totalPages" @click="currentPage++">下一页</Button>
                          </div>
                        </div>
                      </CardContent>
                    </Card>

                    <div v-if="modalOpen" class="modal-mask" role="dialog" aria-modal="true">
                      <form class="record-modal" @submit.prevent="submitRecord">
                        <div class="modal-heading">
                          <div>
                            <h2>{{ editingRecordNo ? '编辑' : '新增' }}{{ entityLabel }}</h2>
                            <p>%s</p>
                          </div>
                          <button type="button" class="close-button" aria-label="关闭" @click="closeModal">×</button>
                        </div>

                        <div class="form-grid">
                          <label v-for="column in editableColumns" :key="column.key">
                            <span>{{ column.title }}</span>
                            <input v-model="draft[column.key]" :required="column.key !== 'remark'" />
                          </label>
                          <label>
                            <span>状态</span>
                            <select v-model="draft.status" required>
                              <option v-for="status in formStatusOptions" :key="status" :value="status">{{ status }}</option>
                            </select>
                          </label>
                        </div>

                        <div class="modal-actions">
                          <Button type="button" variant="outline" @click="closeModal">取消</Button>
                          <Button type="submit" :disabled="saving">{{ saving ? '保存中...' : '保存' }}</Button>
                        </div>
                      </form>
                    </div>
                  </div>
                </template>

                <script setup lang="ts">
                import { %s } from 'vue'
                import { Button } from '@/components/ui/button'
                import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
                import { Badge } from '@/components/ui/badge'
                import {
                  activities,
                  dashboardClass,
                  metrics,
                  orders,
                  visualizationBlocks%s
                } from '@/data/adminData'
                import { columns } from '@/data/table.columns'
                %s

                const entityLabel = '%s'
                const apiBacked = %s
                const records = ref<DashboardRecord[]>(orders.map(item => ({ ...item })))
                const remoteTotal = ref(records.value.length)
                const searchQuery = ref('')
                const statusFilter = ref('')
                const selectedRecordIds = ref<string[]>([])
                const currentPage = ref(1)
                const pageSize = 5
                const modalOpen = ref(false)
                const editingRecordNo = ref<string | null>(null)
                const draft = reactive<DashboardRecord>({ no: '', status: '待处理' })
                const persistenceError = ref('')
                const saving = ref(false)
                const primaryViz = visualizationBlocks[0] ?? {
                  title: '业务趋势',
                  range: '近 7 日',
                  values: [42, 68, 54, 88, 74, 96, 82]
                }

                const statusOptions = computed(() =>
                  [...new Set(records.value.map(item => String(item.status)).filter(Boolean))]
                )
                const formStatusOptions = computed(() =>
                  statusOptions.value.length > 0 ? statusOptions.value : ['待处理', '进行中', '已完成']
                )
                const filteredRows = computed(() => {
                  const keyword = searchQuery.value.trim().toLowerCase()
                  return records.value.filter(row => {
                    const matchesKeyword = !keyword || Object.values(row).some(value =>
                      String(value).toLowerCase().includes(keyword)
                    )
                    const matchesStatus = !statusFilter.value || row.status === statusFilter.value
                    return matchesKeyword && matchesStatus
                  })
                })
                const totalItems = computed(() => apiBacked ? remoteTotal.value : filteredRows.value.length)
                const totalPages = computed(() => Math.max(1, Math.ceil(totalItems.value / pageSize)))
                const visibleRows = computed(() => {
                  if (apiBacked) return records.value
                  const start = (currentPage.value - 1) * pageSize
                  return filteredRows.value.slice(start, start + pageSize)
                })
                const editableColumns = computed(() =>
                  columns.filter(column => !['no', 'status'].includes(column.key))
                )
                const allVisibleSelected = computed(() =>
                  visibleRows.value.length > 0
                    && visibleRows.value.every(row => selectedRecordIds.value.includes(row.no))
                )
                const inventoryWarningCount = computed(() => records.value.filter(row =>
                  Number(row.stock ?? Number.MAX_SAFE_INTEGER) <= 30
                ).length)

                %s

                %s

                function displayValue(value: unknown): string {
                  if (value === null || value === undefined || value === '') return '-'
                  return String(value)
                }

                function toggleSelected(recordNo: string): void {
                  selectedRecordIds.value = selectedRecordIds.value.includes(recordNo)
                    ? selectedRecordIds.value.filter(item => item !== recordNo)
                    : [...selectedRecordIds.value, recordNo]
                }

                function toggleAllVisible(): void {
                  const visibleIds = visibleRows.value.map(row => row.no)
                  if (allVisibleSelected.value) {
                    selectedRecordIds.value = selectedRecordIds.value.filter(id => !visibleIds.includes(id))
                    return
                  }
                  selectedRecordIds.value = [...new Set([...selectedRecordIds.value, ...visibleIds])]
                }

                function resetDraft(): void {
                  for (const key of Object.keys(draft)) delete draft[key]
                }

                function openCreate(): void {
                  resetDraft()
                  editingRecordNo.value = null
                  draft.no = `REC-${String(Date.now()).slice(-6)}`
                  draft.status = formStatusOptions.value[0]
                  for (const column of editableColumns.value) draft[column.key] = ''
                  modalOpen.value = true
                }

                function openEdit(row: DashboardRecord): void {
                  resetDraft()
                  Object.assign(draft, row)
                  editingRecordNo.value = row.no
                  modalOpen.value = true
                }

                function closeModal(): void {
                  modalOpen.value = false
                  editingRecordNo.value = null
                }

                %s

                function resetFilters(): void {
                  searchQuery.value = ''
                  statusFilter.value = ''
                }

                function exportRows(): void {
                  const payload = JSON.stringify(filteredRows.value, null, 2)
                  const url = URL.createObjectURL(new Blob([payload], { type: 'application/json;charset=utf-8' }))
                  const link = document.createElement('a')
                  link.href = url
                  link.download = `${entityLabel}-数据.json`
                  link.click()
                  URL.revokeObjectURL(url)
                }

                function statusVariant(status: string): 'default' | 'secondary' | 'destructive' | 'outline' {
                  if (['已完成', '启用', '在售', '充足'].includes(status)) return 'default'
                  if (['待处理', '待支付'].includes(status)) return 'secondary'
                  if (['已取消', '停用', '下架', '预警'].includes(status)) return 'destructive'
                  return 'outline'
                }
                </script>

                <style scoped>
                .dashboard-view {
                  padding: var(--dashboard-padding);
                  display: flex;
                  flex-direction: column;
                  gap: var(--dashboard-gap);
                }
                .dashboard-toolbar,
                .management-heading,
                .bulk-toolbar,
                .pagination-bar,
                .modal-heading,
                .modal-actions {
                  display: flex;
                  align-items: center;
                  justify-content: space-between;
                  gap: 16px;
                }
                .toolbar-actions,
                .row-actions {
                  display: flex;
                  gap: 8px;
                  flex-wrap: wrap;
                }
                .metric-grid {
                  gap: var(--dashboard-gap);
                }
                .activity-item {
                  display: flex;
                  align-items: center;
                  gap: 12px;
                  padding: 8px;
                  border-radius: var(--panel-radius);
                }
                .activity-item:hover {
                  background: var(--surface-muted);
                }
                .activity-dot {
                  width: 8px;
                  height: 8px;
                  border-radius: 999px;
                  background: hsl(var(--primary));
                }
                .inventory-summary {
                  padding: var(--chip-padding);
                  border-radius: 999px;
                  background: var(--surface-muted);
                  font-size: var(--table-font-size);
                }
                .persistence-alert {
                  padding: 10px 12px;
                  border: 1px solid #f59e0b;
                  border-radius: var(--panel-radius);
                  background: #fffbeb;
                  color: #92400e;
                  font-size: 14px;
                }
                .filter-panel {
                  display: grid;
                  grid-template-columns: minmax(220px, 1fr) minmax(140px, 220px) auto;
                  gap: 12px;
                }
                .filter-input,
                .filter-select,
                .form-grid input,
                .form-grid select {
                  min-height: 40px;
                  width: 100%%;
                  border: 1px solid hsl(var(--border));
                  border-radius: var(--panel-radius);
                  padding: 8px 12px;
                  background: hsl(var(--background));
                }
                .table-scroll {
                  overflow-x: auto;
                }
                .management-table {
                  width: 100%%;
                  min-width: 760px;
                  border-collapse: collapse;
                  font-size: var(--table-font-size);
                }
                .management-table th,
                .management-table td {
                  padding: 12px;
                  text-align: left;
                  border-bottom: 1px solid hsl(var(--border));
                }
                .management-table th {
                  color: hsl(var(--muted-foreground));
                  background: var(--surface-muted);
                  font-weight: 600;
                }
                .selection-cell {
                  width: 44px;
                  text-align: center !important;
                }
                .selection-cell input {
                  width: 16px;
                  height: 16px;
                }
                .empty-state {
                  padding: 40px !important;
                  text-align: center !important;
                  color: hsl(var(--muted-foreground));
                }
                .pagination-bar {
                  padding-top: 16px;
                  color: hsl(var(--muted-foreground));
                  font-size: 14px;
                }
                .modal-mask {
                  position: fixed;
                  inset: 0;
                  z-index: 50;
                  display: grid;
                  place-items: center;
                  padding: 20px;
                  background: rgba(15, 23, 42, 0.55);
                }
                .record-modal {
                  width: min(720px, 100%%);
                  max-height: 90vh;
                  overflow-y: auto;
                  padding: 24px;
                  border-radius: var(--panel-radius);
                  background: hsl(var(--background));
                  box-shadow: 0 24px 80px rgba(15, 23, 42, 0.28);
                }
                .modal-heading h2 {
                  margin: 0;
                  font-size: 20px;
                  font-weight: 700;
                }
                .modal-heading p {
                  margin-top: 4px;
                  color: hsl(var(--muted-foreground));
                  font-size: 14px;
                }
                .close-button {
                  border: 0;
                  background: transparent;
                  font-size: 24px;
                  cursor: pointer;
                }
                .form-grid {
                  display: grid;
                  grid-template-columns: repeat(2, minmax(0, 1fr));
                  gap: 16px;
                  margin: 24px 0;
                }
                .form-grid label {
                  display: grid;
                  gap: 8px;
                  font-size: 14px;
                  font-weight: 600;
                }
                @media (max-width: 720px) {
                  .dashboard-toolbar,
                  .management-heading,
                  .bulk-toolbar,
                  .pagination-bar {
                    align-items: stretch;
                    flex-direction: column;
                  }
                  .filter-panel,
                  .form-grid {
                    grid-template-columns: 1fr;
                  }
                }
                </style>
                """.formatted(
                escape(recipe.domain()),
                escape(recipe.brand()),
                persistence.managementDescription(),
                persistence.modalDescription(),
                persistence.vueImports(),
                persistence.dataRecordImportSuffix(),
                persistence.apiImport(),
                escape(recipe.entityLabel()),
                persistence.apiBacked(),
                persistence.watchers(),
                persistence.supportFunctions(),
                persistence.mutationHandlers()
        );
    }

}
