<template>
  <div id="tokenDashboardPage">
    <section class="dashboard-toolbar">
      <div class="filter-group">
        <button v-for="filter in filters" :key="filter.label" type="button" class="filter-chip">
          <span>{{ filter.label }}</span>
          <strong>{{ filter.value }}</strong>
          <span class="chip-close">x</span>
        </button>
      </div>
      <div class="toolbar-actions">
        <button type="button" class="icon-button">‹</button>
        <button type="button" class="time-range">Last 6 hours</button>
        <button type="button" class="icon-button">›</button>
        <button type="button" class="refresh-button">Refresh&nbsp;&nbsp;5s</button>
      </div>
    </section>

    <section class="dashboard-title">
      <span class="section-kicker">AI OBSERVABILITY</span>
      <h2>Token 消耗与生成质量看板</h2>
    </section>

    <section class="metric-grid">
      <article v-for="metric in metrics" :key="metric.label" class="metric-panel">
        <div class="panel-title">{{ metric.label }}</div>
        <div class="metric-value">
          <span>{{ metric.value }}</span>
          <small v-if="metric.unit">{{ metric.unit }}</small>
        </div>
        <div class="spark-bars" aria-hidden="true">
          <span
            v-for="(bar, index) in metric.spark"
            :key="`${metric.label}-${index}`"
            :style="{ height: `${bar}%` }"
          ></span>
        </div>
      </article>
    </section>

    <section class="chart-grid">
      <article class="chart-panel token-stack-panel">
        <div class="panel-head">
          <div>
            <span class="section-kicker">TOKEN DISTRIBUTION</span>
            <h3>Token 消耗分析</h3>
          </div>
          <span class="panel-status">Live</span>
        </div>
        <div class="stack-chart">
          <div class="y-axis">
            <span>80K</span>
            <span>60K</span>
            <span>40K</span>
            <span>20K</span>
          </div>
          <div class="stack-plot">
            <div class="grid-line grid-line-one"></div>
            <div class="grid-line grid-line-two"></div>
            <div class="grid-line grid-line-three"></div>
            <div v-for="bar in tokenBars" :key="bar.time" class="stack-column" :title="bar.time">
              <span class="segment prompt" :style="{ height: `${bar.prompt}%` }"></span>
              <span class="segment completion" :style="{ height: `${bar.completion}%` }"></span>
              <span class="segment reasoning" :style="{ height: `${bar.reasoning}%` }"></span>
            </div>
          </div>
        </div>
        <div class="chart-times">
          <span>18:00</span>
          <span>19:00</span>
          <span>20:00</span>
          <span>21:00</span>
          <span>22:00</span>
          <span>23:00</span>
        </div>
        <div class="legend-row">
          <span><i class="legend-dot prompt"></i>输入 Token</span>
          <span><i class="legend-dot completion"></i>输出 Token</span>
          <span><i class="legend-dot reasoning"></i>推理 Token</span>
        </div>
      </article>

      <article class="chart-panel">
        <div class="panel-head">
          <div>
            <span class="section-kicker">MODEL MIX</span>
            <h3>Token 类型占比</h3>
          </div>
        </div>
        <div class="pie-wrap">
          <div class="pie-chart" aria-hidden="true"></div>
          <div class="pie-legend">
            <div v-for="item in tokenMix" :key="item.name" class="pie-legend-item">
              <span :style="{ background: item.color }"></span>
              <div>
                <strong>{{ item.percent }}%</strong>
                <small>{{ item.name }}</small>
              </div>
            </div>
          </div>
        </div>
      </article>
    </section>

    <section class="trace-strip">
      <article v-for="item in traceStats" :key="item.label" class="trace-card">
        <span>{{ item.label }}</span>
        <strong>{{ item.value }}</strong>
        <small>{{ item.hint }}</small>
      </article>
    </section>
  </div>
</template>

<script setup lang="ts">
const filters = [
  { label: '渠道', value: 'All' },
  { label: '应用', value: 'All' },
  { label: '用户', value: 'All' },
]

const metrics = [
  {
    label: '总请求数',
    value: '20',
    spark: [8, 18, 12, 22, 16, 28, 18, 20, 40, 36, 44, 76],
  },
  {
    label: '总 Token 消耗',
    value: '88.5',
    unit: 'K',
    spark: [20, 46, 28, 55, 34, 62, 41, 70, 38, 72, 44, 82],
  },
  {
    label: '平均响应时间',
    value: '2.74',
    unit: 'mins',
    spark: [76, 74, 72, 58, 50, 47, 45, 48, 51, 55, 60, 64],
  },
  {
    label: '总错误数',
    value: '0',
    spark: [0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0],
  },
]

const tokenBars = [
  { time: '18:00', prompt: 38, completion: 26, reasoning: 18 },
  { time: '18:20', prompt: 36, completion: 25, reasoning: 19 },
  { time: '18:40', prompt: 35, completion: 24, reasoning: 18 },
  { time: '19:00', prompt: 17, completion: 10, reasoning: 8 },
  { time: '19:20', prompt: 18, completion: 12, reasoning: 9 },
  { time: '19:40', prompt: 40, completion: 29, reasoning: 20 },
  { time: '20:00', prompt: 8, completion: 10, reasoning: 72 },
  { time: '20:20', prompt: 38, completion: 26, reasoning: 23 },
  { time: '20:40', prompt: 16, completion: 12, reasoning: 9 },
  { time: '21:00', prompt: 40, completion: 26, reasoning: 22 },
  { time: '21:20', prompt: 38, completion: 26, reasoning: 21 },
  { time: '21:40', prompt: 17, completion: 13, reasoning: 9 },
  { time: '22:00', prompt: 38, completion: 27, reasoning: 22 },
  { time: '22:20', prompt: 38, completion: 25, reasoning: 21 },
  { time: '22:40', prompt: 28, completion: 20, reasoning: 16 },
  { time: '23:00', prompt: 39, completion: 27, reasoning: 24 },
]

const tokenMix = [
  { name: 'deepseek-chat', percent: 70, color: '#d2a900' },
  { name: 'deepseek-reasoner', percent: 30, color: '#64b95c' },
]

const traceStats = [
  { label: '峰值吞吐', value: '13.8K token/min', hint: '22:18 到达峰值' },
  { label: 'P95 首字响应', value: '1.42s', hint: '较昨日下降 12%' },
  { label: '缓存命中率', value: '41.6%', hint: '模板填充链路表现最佳' },
]
</script>

<style scoped>
#tokenDashboardPage {
  min-height: calc(100vh - 72px);
  padding: 10px 14px 22px;
  color: #d7dde5;
  background:
    linear-gradient(180deg, #0e1419 0%, #10161c 52%, #0b1116 100%);
}

.dashboard-toolbar {
  display: flex;
  justify-content: space-between;
  gap: 14px;
  align-items: center;
  margin-bottom: 10px;
}

.filter-group,
.toolbar-actions {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.filter-chip,
.icon-button,
.time-range,
.refresh-button {
  height: 28px;
  border: 1px solid #2a333d;
  border-radius: 3px;
  color: #c7d0db;
  background: #151b21;
  font-size: 12px;
}

.filter-chip {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 0 9px;
}

.filter-chip span:first-child {
  color: #8d98a5;
}

.filter-chip strong {
  color: #e3e9ef;
}

.chip-close {
  color: #77828d;
}

.icon-button {
  width: 28px;
}

.time-range,
.refresh-button {
  padding: 0 10px;
}

.dashboard-title {
  margin: 6px 0 12px;
}

.section-kicker {
  color: #7f8b97;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.08em;
}

.dashboard-title h2 {
  margin: 4px 0 0;
  color: #edf2f7;
  font-size: 16px;
}

.metric-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  border-top: 1px solid #263039;
  border-left: 1px solid #263039;
}

.metric-panel,
.chart-panel,
.trace-card {
  background:
    linear-gradient(180deg, rgba(23, 31, 37, 0.96), rgba(17, 24, 30, 0.96)),
    #12181e;
  border-right: 1px solid #263039;
  border-bottom: 1px solid #263039;
}

.metric-panel {
  position: relative;
  min-height: 164px;
  overflow: hidden;
  padding: 12px;
}

.panel-title {
  color: #c8d0d8;
  font-size: 12px;
}

.metric-value {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin-top: 28px;
  color: #73c96b;
}

.metric-value span {
  font-size: clamp(48px, 6vw, 88px);
  font-weight: 650;
  line-height: 0.9;
}

.metric-value small {
  font-size: clamp(18px, 2.4vw, 34px);
  font-weight: 650;
}

.spark-bars {
  position: absolute;
  right: 8px;
  bottom: 0;
  left: 72px;
  height: 96px;
  display: grid;
  grid-template-columns: repeat(12, minmax(2px, 1fr));
  align-items: end;
  gap: 7px;
  opacity: 0.54;
}

.spark-bars span {
  min-height: 2px;
  background: rgba(95, 168, 91, 0.5);
  box-shadow: 0 -1px 0 rgba(128, 216, 122, 0.24);
}

.chart-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(340px, 0.55fr);
  border-left: 1px solid #263039;
}

.chart-panel {
  min-height: 300px;
  padding: 14px;
}

.panel-head {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  align-items: flex-start;
  margin-bottom: 18px;
}

.panel-head h3 {
  margin: 4px 0 0;
  color: #edf2f7;
  font-size: 14px;
}

.panel-status {
  padding: 4px 8px;
  border-radius: 999px;
  color: #75d36c;
  background: rgba(117, 211, 108, 0.12);
  font-size: 12px;
}

.stack-chart {
  display: grid;
  grid-template-columns: 44px minmax(0, 1fr);
  height: 196px;
}

.y-axis {
  display: grid;
  align-content: space-between;
  padding: 4px 8px 20px 0;
  color: #7f8b97;
  font-size: 11px;
  text-align: right;
}

.stack-plot {
  position: relative;
  display: grid;
  grid-template-columns: repeat(16, minmax(6px, 1fr));
  align-items: end;
  gap: 8px;
  padding-bottom: 22px;
}

.grid-line {
  position: absolute;
  left: 0;
  right: 0;
  height: 1px;
  background: rgba(92, 106, 118, 0.3);
}

.grid-line-one {
  top: 22%;
}

.grid-line-two {
  top: 48%;
}

.grid-line-three {
  top: 74%;
}

.stack-column {
  position: relative;
  z-index: 1;
  display: flex;
  height: 100%;
  flex-direction: column-reverse;
  justify-content: flex-start;
  overflow: hidden;
  border-radius: 1px 1px 0 0;
}

.segment.prompt {
  background: rgba(88, 180, 91, 0.48);
}

.segment.completion {
  background: rgba(193, 174, 27, 0.32);
}

.segment.reasoning {
  background: rgba(190, 92, 35, 0.35);
}

.chart-times,
.legend-row {
  display: flex;
  gap: 16px;
  color: #7f8b97;
  font-size: 11px;
}

.chart-times {
  justify-content: space-between;
  padding-left: 44px;
}

.legend-row {
  margin-top: 14px;
  flex-wrap: wrap;
}

.legend-dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  margin-right: 6px;
  border-radius: 1px;
}

.pie-wrap {
  display: grid;
  grid-template-columns: 190px minmax(0, 1fr);
  align-items: center;
  gap: 24px;
  min-height: 220px;
}

.pie-chart {
  width: 174px;
  height: 174px;
  border-radius: 999px;
  background: conic-gradient(#d2a900 0 70%, #64b95c 70% 100%);
  box-shadow:
    inset 0 0 0 1px rgba(255, 255, 255, 0.08),
    0 24px 50px rgba(0, 0, 0, 0.24);
}

.pie-legend {
  display: grid;
  gap: 14px;
}

.pie-legend-item {
  display: grid;
  grid-template-columns: 12px minmax(0, 1fr);
  gap: 10px;
  align-items: center;
}

.pie-legend-item > span {
  width: 12px;
  height: 12px;
  border-radius: 3px;
}

.pie-legend-item div {
  display: grid;
  gap: 2px;
}

.pie-legend-item strong {
  color: #edf2f7;
  font-size: 18px;
}

.pie-legend-item small {
  color: #8f9aa6;
}

.trace-strip {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  border-left: 1px solid #263039;
}

.trace-card {
  display: grid;
  gap: 6px;
  padding: 16px;
}

.trace-card span {
  color: #8f9aa6;
  font-size: 12px;
}

.trace-card strong {
  color: #edf2f7;
  font-size: 22px;
}

.trace-card small {
  color: #75d36c;
}

@media (max-width: 1100px) {
  .metric-grid,
  .trace-strip {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .chart-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 720px) {
  #tokenDashboardPage {
    padding: 10px;
  }

  .dashboard-toolbar {
    align-items: flex-start;
    flex-direction: column;
  }

  .metric-grid,
  .trace-strip {
    grid-template-columns: 1fr;
  }

  .pie-wrap {
    grid-template-columns: 1fr;
    justify-items: center;
  }
}
</style>
