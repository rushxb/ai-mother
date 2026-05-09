<template>
  <div id="appManagePage" class="page-shell">
    <section class="page-head">
      <div>
        <h2 class="page-title">应用管理</h2>
      </div>
      <div class="page-summary">
        <span class="summary-label">当前总数</span>
        <span class="summary-value">{{ total }}</span>
      </div>
    </section>

    <a-card class="panel-card" :bordered="false">
      <a-form class="toolbar-form" layout="inline" :model="searchParams" @finish="doSearch">
        <a-form-item label="应用名称">
          <a-input v-model:value="searchParams.appName" allow-clear placeholder="输入应用名称" />
        </a-form-item>
        <a-form-item label="创建者">
          <a-input v-model:value="searchParams.userId" allow-clear placeholder="输入用户ID" />
        </a-form-item>
        <a-form-item label="生成类型">
          <a-select
            v-model:value="searchParams.codeGenType"
            placeholder="选择生成类型"
            style="width: 160px"
          >
            <a-select-option value="">全部</a-select-option>
            <a-select-option
              v-for="option in CODE_GEN_TYPE_OPTIONS"
              :key="option.value"
              :value="option.value"
            >
              {{ option.label }}
            </a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item>
          <a-space>
            <a-button type="primary" html-type="submit">搜索</a-button>
            <a-button @click="resetSearch">重置</a-button>
          </a-space>
        </a-form-item>
      </a-form>
    </a-card>

    <a-card class="panel-card table-card" :bordered="false">
      <a-table
        :columns="columns"
        :data-source="data"
        :pagination="pagination"
        :scroll="{ x: 1200 }"
        :row-key="getAppRowKey"
        @change="doTableChange"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.dataIndex === 'cover'">
            <a-image
              :src="getAppCover(record.cover)"
              :width="72"
              :height="54"
              class="cover-image"
            />
          </template>
          <template v-else-if="column.dataIndex === 'appInfo'">
            <div class="app-info-cell">
              <div class="app-name">{{ record.appName || '未命名应用' }}</div>
              <div class="app-meta">
                <span>ID {{ record.id }}</span>
                <span class="app-meta-divider">•</span>
                <span>{{ formatCodeGenType(record.codeGenType) }}</span>
              </div>
            </div>
          </template>
          <template v-else-if="column.dataIndex === 'initPrompt'">
            <a-tooltip :title="record.initPrompt">
              <div class="prompt-text">{{ record.initPrompt || '暂无提示词' }}</div>
            </a-tooltip>
          </template>
          <template v-else-if="column.dataIndex === 'codeGenType'">
            <a-tag class="soft-tag" color="blue">{{ formatCodeGenType(record.codeGenType) }}</a-tag>
          </template>
          <template v-else-if="column.dataIndex === 'generationState'">
            <div v-if="record.isGenerating" class="generation-state-chip" :class="getGenerationStateClass(record)">
              <span class="generation-state-chip__dot"></span>
              <span>{{ getGenerationStateText(record) }}</span>
            </div>
            <span v-else class="generation-state-idle">空闲</span>
          </template>
          <template v-else-if="column.dataIndex === 'priority'">
            <a-tag v-if="record.priority === 99" color="gold" class="soft-tag">精选</a-tag>
            <span v-else class="priority-text">{{ record.priority || 0 }}</span>
          </template>
          <template v-else-if="column.dataIndex === 'deployedTime'">
            <span v-if="record.deployedTime">
              {{ formatTime(record.deployedTime) }}
            </span>
            <span v-else class="text-gray">未部署</span>
          </template>
          <template v-else-if="column.dataIndex === 'createTime'">
            {{ formatTime(record.createTime) }}
          </template>
          <template v-else-if="column.dataIndex === 'user'">
            <UserInfo :user="record.user" size="small" />
          </template>
          <template v-else-if="column.key === 'action'">
            <a-space :size="8">
              <a-button type="primary" size="small" class="action-button" @click="editApp(record)">
                编辑
              </a-button>
              <a-button
                type="default"
                size="small"
                class="action-button"
                @click="toggleFeatured(record)"
                :class="{ 'featured-btn': record.priority === 99 }"
              >
                {{ record.priority === 99 ? '取消精选' : '精选' }}
              </a-button>
              <a-popconfirm title="确定要删除这个应用吗？" @confirm="deleteApp(record.id)">
                <a-button danger size="small" ghost class="action-button">删除</a-button>
              </a-popconfirm>
            </a-space>
          </template>
        </template>
      </a-table>
    </a-card>
  </div>
</template>

<script lang="ts" setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { listAppVoByPageByAdmin, deleteAppByAdmin, updateAppByAdmin } from '@/api/appController'
import { CODE_GEN_TYPE_OPTIONS, formatCodeGenType } from '@/utils/codeGenTypes'
import { formatTime } from '@/utils/time'
import UserInfo from '@/components/UserInfo.vue'
import { DEFAULT_APP_COVER } from '@/constants/appDefaults'
import { normalizeImageUrl } from '@/utils/url'

const router = useRouter()

const getAppCover = (cover?: string) => normalizeImageUrl(cover) || DEFAULT_APP_COVER
const getGenerationStateText = (app: API.AppVO) => {
  if (app.generatingStage === 'build') {
    return '构建中'
  }
  if (app.generatingStage === 'repair') {
    return '修复中'
  }
  if (app.generatingStage === 'update') {
    return '改修中'
  }
  return '创建中'
}

const getGenerationStateClass = (app: API.AppVO) => {
  if (app.generatingStage === 'build') {
    return 'is-build'
  }
  if (app.generatingStage === 'repair') {
    return 'is-repair'
  }
  return app.generatingStage === 'update' ? 'is-update' : 'is-create'
}

const columns = [
  {
    title: '应用信息',
    dataIndex: 'appInfo',
    width: 230,
    fixed: 'left',
  },
  {
    title: 'ID',
    dataIndex: 'id',
    width: 80,
  },
  {
    title: '封面',
    dataIndex: 'cover',
    width: 100,
  },
  {
    title: '初始提示词',
    dataIndex: 'initPrompt',
    width: 200,
  },
  {
    title: '生成类型',
    dataIndex: 'codeGenType',
    width: 100,
  },
  {
    title: '进度',
    dataIndex: 'generationState',
    width: 110,
  },
  {
    title: '优先级',
    dataIndex: 'priority',
    width: 80,
  },
  {
    title: '部署时间',
    dataIndex: 'deployedTime',
    width: 160,
  },
  {
    title: '创建者',
    dataIndex: 'user',
    width: 120,
  },
  {
    title: '创建时间',
    dataIndex: 'createTime',
    width: 160,
  },
  {
    title: '操作',
    key: 'action',
    width: 200,
    fixed: 'right',
  },
]

// 数据
const data = ref<API.AppVO[]>([])
const total = ref(0)

// 搜索条件
const searchParams = reactive<API.AppQueryRequest>({
  pageNum: 1,
  pageSize: 10,
})

const getAppRowKey = (record: API.AppVO) => record.id ?? ''

// 获取数据
const fetchData = async () => {
  try {
    const res = await listAppVoByPageByAdmin({
      ...searchParams,
    })
    if (res.data.data) {
      data.value = res.data.data.records ?? []
      total.value = res.data.data.totalRow ?? 0
    } else {
      message.error('获取数据失败，' + res.data.message)
    }
  } catch (error) {
    console.error('获取数据失败：', error)
    message.error('获取数据失败')
  }
}

// 页面加载时请求一次
onMounted(() => {
  fetchData()
})

// 分页参数
const pagination = computed(() => {
  return {
    current: searchParams.pageNum ?? 1,
    pageSize: searchParams.pageSize ?? 10,
    total: total.value,
    showSizeChanger: true,
    showTotal: (total: number) => `共 ${total} 条`,
  }
})

// 表格变化处理
const doTableChange = (page: { current: number; pageSize: number }) => {
  searchParams.pageNum = page.current
  searchParams.pageSize = page.pageSize
  fetchData()
}

// 搜索
const doSearch = () => {
  // 重置页码
  searchParams.pageNum = 1
  fetchData()
}

const resetSearch = () => {
  searchParams.appName = undefined
  searchParams.userId = undefined
  searchParams.codeGenType = undefined
  searchParams.pageNum = 1
  fetchData()
}

// 编辑应用
const editApp = (app: API.AppVO) => {
  router.push(`/app/edit/${app.id}`)
}

// 切换精选状态
const toggleFeatured = async (app: API.AppVO) => {
  if (!app.id) return

  const newPriority = app.priority === 99 ? 0 : 99

  try {
    const res = await updateAppByAdmin({
      id: app.id,
      priority: newPriority,
    })

    if (res.data.code === 0) {
      message.success(newPriority === 99 ? '已设为精选' : '已取消精选')
      // 刷新数据
      fetchData()
    } else {
      message.error('操作失败：' + res.data.message)
    }
  } catch (error) {
    console.error('操作失败：', error)
    message.error('操作失败')
  }
}

// 删除应用
const deleteApp = async (id: number | undefined) => {
  if (!id) return

  try {
    const res = await deleteAppByAdmin({ id })
    if (res.data.code === 0) {
      message.success('删除成功')
      // 刷新数据
      fetchData()
    } else {
      message.error('删除失败：' + res.data.message)
    }
  } catch (error) {
    console.error('删除失败：', error)
    message.error('删除失败')
  }
}
</script>

<style scoped>
#appManagePage {
  padding: 8px 24px 24px;
  background: #f5f7fb;
  margin-top: 0;
}

.page-shell {
  display: grid;
  gap: 14px;
}

.page-head {
  position: relative;
  display: flex;
  align-items: flex-start;
  justify-content: flex-start;
  padding: 0 4px;
  min-height: 32px;
  padding-right: 132px;
}

.page-title {
  margin: 0;
  font-size: 22px;
  line-height: 1.2;
  color: #0f172a;
  font-weight: 700;
}

.page-summary {
  position: absolute;
  z-index: 20;
  top: -2px;
  right: 4px;
  min-width: 112px;
  padding: 12px 14px;
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.86);
  box-shadow: 0 14px 34px rgba(15, 23, 42, 0.1);
  border: 1px solid rgba(148, 163, 184, 0.16);
  backdrop-filter: blur(12px);
}

.summary-label {
  display: block;
  font-size: 12px;
  color: #94a3b8;
}

.summary-value {
  display: block;
  margin-top: 2px;
  font-size: 20px;
  font-weight: 700;
  color: #0f172a;
}

.panel-card {
  border-radius: 18px;
  box-shadow: 0 12px 40px rgba(15, 23, 42, 0.06);
  border: 1px solid rgba(148, 163, 184, 0.12);
}

.toolbar-form {
  display: flex;
  flex-wrap: wrap;
  gap: 12px 8px;
  align-items: center;
}

.table-card :deep(.ant-card-body) {
  padding-top: 8px;
}

.table-card :deep(.ant-table) {
  background: transparent;
}

.table-card :deep(.ant-table-container) {
  border-radius: 16px;
  overflow: hidden;
}

.table-card :deep(.ant-table-thead > tr > th) {
  background: #f8fafc;
  color: #475569;
  font-weight: 600;
  border-bottom: 1px solid rgba(148, 163, 184, 0.16);
}

.table-card :deep(.ant-table-tbody > tr > td) {
  border-bottom: 1px solid rgba(226, 232, 240, 0.8);
  vertical-align: middle;
}

.table-card :deep(.ant-table-tbody > tr:hover > td) {
  background: rgba(59, 130, 246, 0.035);
}

.app-info-cell {
  min-width: 0;
}

.app-name {
  color: #0f172a;
  font-weight: 600;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.app-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 4px;
  color: #94a3b8;
  font-size: 12px;
}

.app-meta-divider {
  opacity: 0.6;
}

.prompt-text {
  max-width: 200px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: #334155;
}

.text-gray {
  color: #94a3b8;
}

.featured-btn {
  background: #faad14;
  border-color: #faad14;
  color: white;
}

.featured-btn:hover {
  background: #d48806;
  border-color: #d48806;
}

.soft-tag {
  border-radius: 999px;
  border: 0;
}

.priority-text {
  color: #475569;
}

.action-button {
  border-radius: 999px;
}

.generation-state-chip {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 6px 11px;
  border-radius: 999px;
  border: 1px solid rgba(148, 163, 184, 0.2);
  background: rgba(248, 250, 252, 0.82);
  color: #526579;
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.02em;
}

.generation-state-chip.is-update {
  background: rgba(243, 247, 250, 0.88);
  color: #406279;
}

.generation-state-chip.is-build {
  background: rgba(239, 246, 255, 0.9);
  color: #1d4ed8;
}

.generation-state-chip.is-repair {
  background: rgba(255, 251, 235, 0.92);
  color: #b45309;
}

.generation-state-chip__dot {
  width: 6px;
  height: 6px;
  border-radius: 999px;
  background: currentColor;
  opacity: 0.8;
  animation: statePulse 1.9s ease-in-out infinite;
}

.generation-state-idle {
  color: #9aa5b1;
}

:deep(.ant-table-tbody > tr > td) {
  vertical-align: middle;
}

:deep(.cover-image .ant-image-img) {
  border-radius: 16px;
  object-fit: cover;
}

:deep(.ant-input),
:deep(.ant-select-selector),
:deep(.ant-btn) {
  border-radius: 999px !important;
}

:deep(.ant-form-item) {
  margin-bottom: 0;
}

@keyframes statePulse {
  0%,
  100% {
    transform: scale(1);
    opacity: 0.58;
  }
  50% {
    transform: scale(1.18);
    opacity: 1;
  }
}

@media (max-width: 768px) {
  #appManagePage {
    padding: 8px 16px 16px;
  }

  .page-head {
    min-height: 0;
    flex-direction: column;
    padding-right: 4px;
  }

  .page-summary {
    position: static;
    width: 100%;
  }
}
</style>
