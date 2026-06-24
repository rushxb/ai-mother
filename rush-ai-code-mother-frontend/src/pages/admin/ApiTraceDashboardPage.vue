<template>
  <div id="apiTraceDashboardPage">
    <aside class="trace-sidebar">
      <section class="sidebar-section">
        <div class="sidebar-title">依赖服务</div>
        <div class="sidebar-tabs">
          <button type="button" class="active">全部</button>
          <button type="button">异常</button>
          <button type="button">慢调用</button>
        </div>
        <label class="search-box">
          <span>⌕</span>
          <input value="" placeholder="搜索服务或接口" readonly />
        </label>
        <button v-for="service in services" :key="service.name" type="button" class="service-item" :class="{ active: service.active }">
          <strong>{{ service.name }}</strong>
          <span>{{ service.count }} / {{ service.rate }}</span>
        </button>
      </section>

      <section class="sidebar-section">
        <div class="sidebar-title">主调服务</div>
        <div class="sidebar-tabs">
          <button type="button" class="active">实时</button>
          <button type="button">异常</button>
          <button type="button">高延迟</button>
        </div>
        <button v-for="caller in callers" :key="caller.name" type="button" class="service-item compact">
          <strong>{{ caller.name }}</strong>
          <span>{{ caller.count }} / {{ caller.rate }}</span>
        </button>
      </section>
    </aside>

    <main class="trace-main">
      <section class="trace-header">
        <div>
          <span class="page-kicker">ADMIN APM</span>
          <h2>接口调用观测</h2>
        </div>
        <div class="header-actions">
          <button type="button">最近 30 分钟</button>
          <button type="button" class="primary-action">刷新</button>
        </div>
      </section>

      <nav class="trace-tabs">
        <button v-for="tab in tabs" :key="tab" type="button" :class="{ active: tab === '接口' }">
          {{ tab }}
        </button>
      </nav>

      <section class="filter-line">
        <button type="button" class="select-pill">接口&nbsp;&nbsp;ALL</button>
        <button type="button" class="select-pill">模型&nbsp;&nbsp;ALL</button>
        <button type="button" class="select-pill">状态&nbsp;&nbsp;ALL</button>
        <span>静态演示数据，展示接口吞吐、错误和耗时趋势。</span>
      </section>

      <section class="endpoint-list">
        <article v-for="endpoint in endpoints" :key="endpoint.path" class="endpoint-card">
          <div class="endpoint-title">
            <strong>{{ endpoint.path }}</strong>
            <span>{{ endpoint.method }}</span>
          </div>
          <div class="mini-chart-grid">
            <MiniTrendCard
              v-for="metric in endpoint.metrics"
              :key="`${endpoint.path}-${metric.name}`"
              :metric="metric"
            />
          </div>
        </article>
      </section>
    </main>
  </div>
</template>

<script setup lang="ts">
import { computed, defineComponent, h } from 'vue'

interface TrendMetric {
  name: string
  value: string
  color: string
  points: number[]
  unit?: string
}

const services = [
  { name: 'backend-app', count: 20, rate: '100%', active: true },
  { name: 'ai-gateway', count: 12, rate: '99.9%' },
  { name: 'code-sandbox', count: 8, rate: '99.3%' },
]

const callers = [
  { name: 'WEB', count: 188, rate: '100%' },
  { name: '10.60.0.11', count: 92, rate: '100%' },
  { name: 'scheduler', count: 31, rate: '98.7%' },
]

const tabs = ['概览', 'SQL 分析', '异常分析', '调用追踪', '接口', '调用拓扑']

const endpoints: Array<{ path: string; method: string; metrics: TrendMetric[] }> = [
  {
    path: '/api/app/admin/list/page/vo',
    method: 'POST',
    metrics: [
      { name: '请求数 / min', value: '5.40', color: '#746bff', points: [22, 24, 28, 35, 48, 53, 57] },
      { name: '错误数 / min', value: '0.00', color: '#ef5b6c', points: [4, 4, 4, 4, 4, 4, 4] },
      { name: '耗时 / ms', value: '1.8s', color: '#39c7c4', points: [72, 68, 61, 56, 50, 47, 45] },
    ],
  },
  {
    path: '/api/app/chat/gen/code',
    method: 'POST',
    metrics: [
      { name: '请求数 / min', value: '4.00', color: '#746bff', points: [58, 58, 58, 58, 56, 14, 10] },
      { name: '错误数 / min', value: '0.00', color: '#ef5b6c', points: [4, 4, 4, 4, 4, 4, 4] },
      { name: '耗时 / ms', value: '6.2m', color: '#39c7c4', points: [16, 20, 28, 35, 44, 66, 82] },
    ],
  },
  {
    path: '/api/app/copy',
    method: 'POST',
    metrics: [
      { name: '请求数 / min', value: '1.60', color: '#746bff', points: [8, 10, 10, 12, 18, 24, 27] },
      { name: '错误数 / min', value: '0.00', color: '#ef5b6c', points: [4, 4, 4, 4, 4, 4, 4] },
      { name: '耗时 / ms', value: '842ms', color: '#39c7c4', points: [34, 30, 36, 32, 28, 24, 22] },
    ],
  },
  {
    path: '/api/model/admin/list',
    method: 'GET',
    metrics: [
      { name: '请求数 / min', value: '2.10', color: '#746bff', points: [36, 38, 36, 39, 42, 41, 44] },
      { name: '错误数 / min', value: '0.00', color: '#ef5b6c', points: [4, 4, 4, 4, 4, 4, 4] },
      { name: '耗时 / ms', value: '138ms', color: '#39c7c4', points: [18, 22, 21, 19, 18, 17, 18] },
    ],
  },
]

const MiniTrendCard = defineComponent({
  name: 'MiniTrendCard',
  props: {
    metric: {
      type: Object as () => TrendMetric,
      required: true,
    },
  },
  setup(props) {
    const path = computed(() => {
      const points = props.metric.points
      const max = Math.max(...points, 1)
      const min = Math.min(...points)
      return points
        .map((point, index) => {
          const x = (index / Math.max(points.length - 1, 1)) * 100
          const y = 82 - ((point - min) / Math.max(max - min, 1)) * 58
          return `${index === 0 ? 'M' : 'L'} ${x.toFixed(1)} ${y.toFixed(1)}`
        })
        .join(' ')
    })

    return () =>
      h('div', { class: 'mini-card' }, [
        h('div', { class: 'mini-head' }, [
          h('span', props.metric.name),
          h('div', { class: 'mini-tools' }, [h('i'), h('i'), h('i')]),
        ]),
        h('strong', { style: { color: props.metric.color } }, props.metric.value),
        h(
          'svg',
          {
            class: 'mini-chart',
            viewBox: '0 0 100 88',
            preserveAspectRatio: 'none',
            role: 'img',
            'aria-label': props.metric.name,
          },
          [
            h('path', {
              d: 'M 0 24 L 100 24 M 0 52 L 100 52 M 0 80 L 100 80',
              class: 'grid-path',
            }),
            h('path', {
              d: path.value,
              fill: 'none',
              stroke: props.metric.color,
              'stroke-width': 2.6,
              'stroke-linecap': 'round',
              'stroke-linejoin': 'round',
            }),
          ],
        ),
        h('div', { class: 'mini-time' }, [h('span', '22:00'), h('span', '22:30'), h('span', '23:00')]),
      ])
  },
})
</script>

<style scoped>
#apiTraceDashboardPage {
  min-height: calc(100vh - 72px);
  display: grid;
  grid-template-columns: 260px minmax(0, 1fr);
  color: #1f2937;
  background: #f4f6f9;
}

.trace-sidebar {
  display: grid;
  align-content: start;
  gap: 12px;
  padding: 14px 12px;
  border-right: 1px solid #dce3ec;
  background: #f9fbfd;
}

.sidebar-section {
  display: grid;
  gap: 10px;
}

.sidebar-title {
  color: #27364a;
  font-size: 13px;
  font-weight: 750;
}

.sidebar-tabs {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  overflow: hidden;
  border: 1px solid #dce3ec;
  border-radius: 6px;
  background: #fff;
}

.sidebar-tabs button {
  height: 30px;
  border: 0;
  border-right: 1px solid #e5ebf2;
  color: #6b7685;
  background: transparent;
  font-size: 12px;
}

.sidebar-tabs button:last-child {
  border-right: 0;
}

.sidebar-tabs .active {
  color: #0f7bff;
  background: #eef6ff;
}

.search-box {
  height: 34px;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 10px;
  border: 1px solid #dce3ec;
  border-radius: 6px;
  background: #fff;
}

.search-box span {
  color: #91a0b2;
}

.search-box input {
  width: 100%;
  border: 0;
  outline: 0;
  color: #778396;
  background: transparent;
  font-size: 12px;
}

.service-item {
  display: grid;
  gap: 4px;
  width: 100%;
  padding: 10px;
  border: 1px solid transparent;
  border-radius: 7px;
  text-align: left;
  background: transparent;
}

.service-item.active {
  border-color: #cfe3ff;
  background: #eef6ff;
}

.service-item strong {
  overflow: hidden;
  color: #26374d;
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.service-item span {
  color: #8b98a8;
  font-size: 12px;
}

.service-item.compact {
  padding-block: 8px;
}

.trace-main {
  min-width: 0;
  display: grid;
  align-content: start;
  gap: 12px;
  padding: 14px;
}

.trace-header {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  align-items: center;
  padding: 12px 14px;
  border: 1px solid #dce3ec;
  border-radius: 8px;
  background: #fff;
}

.page-kicker {
  color: #6a84a4;
  font-size: 11px;
  font-weight: 800;
  letter-spacing: 0.12em;
}

.trace-header h2 {
  margin: 2px 0 0;
  color: #172033;
  font-size: 20px;
}

.header-actions {
  display: flex;
  gap: 8px;
}

.header-actions button,
.select-pill {
  height: 34px;
  padding: 0 12px;
  border: 1px solid #d9e2ec;
  border-radius: 6px;
  color: #415168;
  background: #fff;
}

.header-actions .primary-action {
  color: #fff;
  border-color: #1685ff;
  background: #1685ff;
}

.trace-tabs {
  display: flex;
  gap: 18px;
  padding: 0 4px;
  border-bottom: 1px solid #dce3ec;
}

.trace-tabs button {
  position: relative;
  height: 40px;
  border: 0;
  color: #5f6f84;
  background: transparent;
  font-weight: 650;
}

.trace-tabs button.active {
  color: #0f7bff;
}

.trace-tabs button.active::after {
  content: '';
  position: absolute;
  right: 0;
  bottom: -1px;
  left: 0;
  height: 2px;
  background: #0f7bff;
}

.filter-line {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.filter-line span {
  margin-left: 4px;
  color: #8a98aa;
  font-size: 12px;
}

.endpoint-list {
  display: grid;
  gap: 12px;
}

.endpoint-card {
  display: grid;
  gap: 10px;
}

.endpoint-title {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #1f2d40;
}

.endpoint-title strong {
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 13px;
}

.endpoint-title span {
  padding: 2px 6px;
  border-radius: 999px;
  color: #0f7bff;
  background: #eaf4ff;
  font-size: 11px;
  font-weight: 800;
}

.mini-chart-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
}

.mini-card {
  min-height: 158px;
  padding: 10px 12px 8px;
  border: 1px solid #dce3ec;
  border-radius: 7px;
  background: #fff;
  box-shadow: 0 8px 24px rgba(31, 45, 64, 0.04);
}

.mini-head {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  color: #334155;
  font-size: 12px;
  font-weight: 700;
}

.mini-tools {
  display: inline-flex;
  gap: 4px;
}

.mini-tools i {
  width: 5px;
  height: 5px;
  border-radius: 999px;
  background: #9aa8b8;
}

.mini-card strong {
  display: block;
  margin-top: 8px;
  font-size: 13px;
}

.mini-chart {
  width: 100%;
  height: 88px;
  margin-top: 2px;
  overflow: visible;
}

.grid-path {
  stroke: #edf1f6;
  stroke-width: 1;
}

.mini-time {
  display: flex;
  justify-content: space-between;
  color: #9aa8b8;
  font-size: 10px;
}

@media (max-width: 1100px) {
  #apiTraceDashboardPage {
    grid-template-columns: 1fr;
  }

  .trace-sidebar {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    border-right: 0;
    border-bottom: 1px solid #dce3ec;
  }
}

@media (max-width: 820px) {
  .mini-chart-grid,
  .trace-sidebar {
    grid-template-columns: 1fr;
  }

  .trace-header {
    align-items: flex-start;
    flex-direction: column;
  }

  .trace-tabs {
    overflow-x: auto;
  }

  .trace-tabs button {
    flex: 0 0 auto;
  }
}
</style>
