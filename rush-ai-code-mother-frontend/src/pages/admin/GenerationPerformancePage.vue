<template>
  <div id="generationPerformancePage" class="page-shell">
    <section class="page-head">
      <div>
        <h2 class="page-title">生成耗时看板</h2>
        <p class="page-desc">跟踪 AI 生成链路各阶段耗时，定位慢在模型、依赖、构建还是运行时验证</p>
      </div>
      <a-space>
        <a-select v-model:value="limit" class="limit-select" @change="fetchData">
          <a-select-option :value="20">最近 20 条</a-select-option>
          <a-select-option :value="50">最近 50 条</a-select-option>
          <a-select-option :value="100">最近 100 条</a-select-option>
        </a-select>
        <a-button type="primary" :loading="loading" @click="fetchData">刷新</a-button>
      </a-space>
    </section>

    <section class="metric-grid">
      <a-card v-for="metric in metrics" :key="metric.label" class="metric-card" :bordered="false">
        <span class="metric-label">{{ metric.label }}</span>
        <strong class="metric-value">{{ metric.value }}</strong>
        <span class="metric-hint">{{ metric.hint }}</span>
      </a-card>
    </section>

    <a-row :gutter="[16, 16]">
      <a-col :xs="24" :xl="9">
        <a-card class="panel-card" :bordered="false" title="阶段耗时排行">
          <a-empty v-if="!stageStats.length" description="暂无阶段数据" />
          <div v-else class="stage-list">
            <div v-for="stage in stageStats" :key="stage.stage" class="stage-row">
              <div class="stage-topline">
                <span class="stage-name">{{ formatStage(stage.stage) }}</span>
                <span class="stage-time">P90 {{ formatDuration(stage.p90DurationMs) }}</span>
              </div>
              <a-progress
                :percent="stagePercent(stage.p90DurationMs)"
                :show-info="false"
                size="small"
              />
              <div class="stage-meta">
                <span>次数 {{ stage.count ?? 0 }}</span>
                <span>平均 {{ formatDuration(stage.avgDurationMs) }}</span>
                <span>最大 {{ formatDuration(stage.maxDurationMs) }}</span>
              </div>
            </div>
          </div>
        </a-card>
      </a-col>
      <a-col :xs="24" :xl="15">
        <a-card class="panel-card table-card" :bordered="false" title="最近生成任务">
          <a-table
            :columns="columns"
            :data-source="recentTasks"
            :loading="loading"
            :pagination="{ pageSize: 10, showSizeChanger: false }"
            :row-key="(record: API.GenerationPerformanceTaskVO) => record.taskId ?? ''"
          >
            <template #bodyCell="{ column, record }">
              <template v-if="column.dataIndex === 'taskId'">
                <div class="task-cell">
                  <span class="task-id">{{ record.taskId }}</span>
                  <span class="task-sub">App {{ record.appId ?? '-' }} / User {{ record.userId ?? '-' }}</span>
                </div>
              </template>
              <template v-else-if="column.dataIndex === 'route'">
                <a-tag class="soft-tag" :color="routeColor(record.route)">
                  {{ formatRoute(record.route) }}
                </a-tag>
              </template>
              <template v-else-if="column.dataIndex === 'status'">
                <a-tag class="soft-tag" :color="statusColor(record.status)">
                  {{ formatStatus(record.status) }}
                </a-tag>
              </template>
              <template v-else-if="column.dataIndex === 'totalDurationMs'">
                <strong>{{ formatDuration(record.totalDurationMs) }}</strong>
              </template>
              <template v-else-if="column.dataIndex === 'startTime'">
                <span class="time-text">{{ formatTime(record.startTime) }}</span>
              </template>
              <template v-else-if="column.key === 'spans'">
                <a-popover trigger="click" placement="leftTop">
                  <template #content>
                    <div class="span-popover">
                      <div v-for="span in record.spans ?? []" :key="`${span.stage}-${span.durationMs}`" class="span-row">
                        <span>{{ formatStage(span.stage) }}</span>
                        <strong>{{ formatDuration(span.durationMs) }}</strong>
                        <a-tag class="soft-tag" :color="statusColor(span.status)">
                          {{ formatStatus(span.status) }}
                        </a-tag>
                      </div>
                    </div>
                  </template>
                  <a-button size="small" ghost>阶段</a-button>
                </a-popover>
              </template>
            </template>
          </a-table>
        </a-card>
      </a-col>
    </a-row>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { message } from 'ant-design-vue'
import { getGenerationPerformanceSummary } from '@/api/generationPerformanceController'

const loading = ref(false)
const limit = ref(50)
const summary = ref<API.GenerationPerformanceSummaryVO>({})

const columns = [
  { title: '任务', dataIndex: 'taskId', width: 260 },
  { title: '路径', dataIndex: 'route', width: 130 },
  { title: '状态', dataIndex: 'status', width: 90 },
  { title: '总耗时', dataIndex: 'totalDurationMs', width: 110 },
  { title: '开始时间', dataIndex: 'startTime', width: 160 },
  { title: '明细', key: 'spans', width: 80 },
]

const recentTasks = computed(() => summary.value.recentTasks ?? [])
const stageStats = computed(() => summary.value.stageStats ?? [])

const metrics = computed(() => [
  {
    label: '任务数',
    value: summary.value.taskCount ?? 0,
    hint: `成功 ${summary.value.successCount ?? 0} / 失败 ${summary.value.failedCount ?? 0}`,
  },
  {
    label: '运行中',
    value: summary.value.runningCount ?? 0,
    hint: '当前仍未结束的生成任务',
  },
  {
    label: '平均总耗时',
    value: formatDuration(summary.value.avgTotalDurationMs),
    hint: '最近保留任务的完成均值',
  },
  {
    label: 'P50 总耗时',
    value: formatDuration(summary.value.p50TotalDurationMs),
    hint: '一半任务低于该耗时',
  },
  {
    label: 'P90 总耗时',
    value: formatDuration(summary.value.p90TotalDurationMs),
    hint: '慢任务优化优先看这里',
  },
])

const maxStageP90 = computed(() => Math.max(1, ...stageStats.value.map((item) => item.p90DurationMs ?? 0)))

const fetchData = async () => {
  loading.value = true
  try {
    const res = await getGenerationPerformanceSummary({ limit: limit.value })
    if (res.data.code === 0) {
      summary.value = res.data.data ?? {}
    } else {
      message.error('获取生成耗时失败，' + res.data.message)
    }
  } finally {
    loading.value = false
  }
}

const stagePercent = (duration?: number) => {
  return Math.max(3, Math.round(((duration ?? 0) / maxStageP90.value) * 100))
}

const formatDuration = (durationMs?: number) => {
  const value = durationMs ?? 0
  if (value < 1000) return `${value}ms`
  const seconds = value / 1000
  if (seconds < 60) return `${seconds.toFixed(1)}s`
  const minutes = Math.floor(seconds / 60)
  const restSeconds = Math.round(seconds % 60)
  return `${minutes}m ${restSeconds}s`
}

const formatTime = (value?: string) => {
  if (!value) return '-'
  return value.replace('T', ' ').slice(0, 19)
}

const formatStage = (stage?: string) => {
  const map: Record<string, string> = {
    heavy_prepare: '重型规划',
    llm_generation: '模型生成',
    build_validation: '构建验证',
    pnpm_install: '依赖安装',
    light_validate: '轻量校验',
    light_build: '轻量构建',
    full_build: '全量构建',
    dev_server_validation: '运行时验证',
    finalization: '结果整理',
    slot_fill: '模板填充',
    lightweight_edit: '轻量编辑',
  }
  return map[stage ?? ''] ?? stage ?? '-'
}

const formatRoute = (route?: string) => {
  const map: Record<string, string> = {
    heavy_generation: '重型生成',
    slot_fill: '模板填充',
    lightweight_edit: '轻量编辑',
  }
  return map[route ?? ''] ?? route ?? '-'
}

const formatStatus = (status?: string) => {
  const map: Record<string, string> = {
    success: '成功',
    failed: '失败',
    running: '运行中',
    cancelled: '取消',
    timeout: '超时',
  }
  return map[status ?? ''] ?? status ?? '-'
}

const routeColor = (route?: string) => {
  const map: Record<string, string> = {
    heavy_generation: 'purple',
    slot_fill: 'blue',
    lightweight_edit: 'green',
  }
  return map[route ?? ''] ?? 'default'
}

const statusColor = (status?: string) => {
  const map: Record<string, string> = {
    success: 'green',
    failed: 'red',
    running: 'blue',
    cancelled: 'orange',
    timeout: 'volcano',
  }
  return map[status ?? ''] ?? 'default'
}

onMounted(fetchData)
</script>

<style scoped>
#generationPerformancePage {
  min-height: calc(100vh - 72px);
  padding: 8px 24px 24px;
  background: #f5f7fb;
}

.page-shell {
  display: grid;
  gap: 16px;
}

.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding: 0 4px;
}

.page-title {
  margin: 0;
  color: #0f172a;
  font-size: 22px;
  line-height: 1.2;
  font-weight: 700;
}

.page-desc {
  margin: 4px 0 0;
  color: #64748b;
  font-size: 14px;
}

.limit-select {
  width: 132px;
}

.metric-grid {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 14px;
}

.metric-card,
.panel-card {
  border-radius: 8px;
  border: 1px solid rgba(148, 163, 184, 0.14);
  box-shadow: 0 12px 36px rgba(15, 23, 42, 0.06);
}

.metric-card :deep(.ant-card-body) {
  display: grid;
  gap: 4px;
  padding: 16px 18px;
}

.metric-label {
  color: #64748b;
  font-size: 13px;
}

.metric-value {
  color: #0f172a;
  font-size: 24px;
  line-height: 1.15;
}

.metric-hint {
  color: #94a3b8;
  font-size: 12px;
}

.stage-list {
  display: grid;
  gap: 16px;
}

.stage-row {
  display: grid;
  gap: 7px;
}

.stage-topline,
.stage-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.stage-name {
  color: #0f172a;
  font-weight: 650;
}

.stage-time {
  color: #2563eb;
  font-size: 13px;
  font-weight: 650;
}

.stage-meta {
  color: #94a3b8;
  font-size: 12px;
}

.table-card :deep(.ant-card-body) {
  padding-top: 6px;
}

.table-card :deep(.ant-table-container) {
  overflow: hidden;
  border-radius: 8px;
}

.table-card :deep(.ant-table-thead > tr > th) {
  background: #f8fafc;
  color: #475569;
  font-weight: 650;
}

.task-cell {
  display: grid;
  gap: 2px;
  min-width: 0;
}

.task-id {
  overflow: hidden;
  color: #0f172a;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.task-sub,
.time-text {
  color: #94a3b8;
  font-size: 12px;
}

.soft-tag {
  border: 0;
  border-radius: 999px;
}

.span-popover {
  display: grid;
  width: 320px;
  max-width: 70vw;
  gap: 8px;
}

.span-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto auto;
  align-items: center;
  gap: 10px;
}

@media (max-width: 1080px) {
  .metric-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 720px) {
  #generationPerformancePage {
    padding: 8px 16px 16px;
  }

  .page-head {
    flex-direction: column;
  }

  .metric-grid {
    grid-template-columns: 1fr;
  }
}
</style>
