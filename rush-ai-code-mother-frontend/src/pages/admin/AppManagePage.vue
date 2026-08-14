<template>
  <AdminPageFrame
    id="appManagePage"
    eyebrow="APPLICATION OPERATIONS"
    title="应用管理"
    description="集中管理应用资产、生成进度、部署状态与精选策略，保持平台内容清晰可控。"
  >
    <template #actions>
      <AdminSummaryBadge label="应用总数" :value="total" tone="blue" />
    </template>

    <AdminFilterPanel description="通过名称、创建者和生成类型组合筛选，快速定位应用资产。">
      <a-form class="toolbar-form" layout="inline" :model="searchParams" @finish="doSearch">
        <a-form-item label="应用名称">
          <a-input v-model:value="searchParams.appName" allow-clear placeholder="输入应用名称" />
        </a-form-item>
        <a-form-item label="创建者">
          <a-input-number
            v-model:value="searchParams.userId"
            :min="1"
            :precision="0"
            placeholder="输入用户 ID"
          />
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
    </AdminFilterPanel>

    <AdminDataPanel title="应用资产" description="同步展示生成状态、部署时间、创建者和运营优先级。">
      <a-alert
        v-if="loadError"
        type="error"
        show-icon
        closable
        :message="loadError"
        class="load-error"
        @close="loadError = ''"
      />
      <a-table
        :columns="columns"
        :data-source="data"
        :loading="loading"
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
            <div
              v-if="record.isGenerating"
              class="generation-state-chip"
              :class="getGenerationStateClass(record)"
            >
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
              <a-button
                type="primary"
                size="small"
                class="action-button"
                :disabled="isAppBusy(record.id)"
                @click="editApp(record)"
              >
                编辑
              </a-button>
              <a-button
                type="default"
                size="small"
                class="action-button"
                :loading="isUpdatingFeatured(record.id)"
                :disabled="isDeletingApp(record.id)"
                @click="toggleFeatured(record)"
                :class="{ 'featured-btn': record.priority === 99 }"
              >
                {{ record.priority === 99 ? '取消精选' : '精选' }}
              </a-button>
              <a-popconfirm
                title="确定要删除这个应用吗？"
                :disabled="isAppBusy(record.id)"
                @confirm="deleteApp(record.id)"
              >
                <a-button
                  danger
                  size="small"
                  ghost
                  class="action-button"
                  :loading="isDeletingApp(record.id)"
                  :disabled="isUpdatingFeatured(record.id)"
                >
                  删除
                </a-button>
              </a-popconfirm>
            </a-space>
          </template>
        </template>
      </a-table>
    </AdminDataPanel>
  </AdminPageFrame>
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
import { useLatestRequest } from '@/composables/useLatestRequest'
import AdminPageFrame from '@/components/admin/AdminPageFrame.vue'
import AdminFilterPanel from '@/components/admin/AdminFilterPanel.vue'
import AdminDataPanel from '@/components/admin/AdminDataPanel.vue'
import AdminSummaryBadge from '@/components/admin/AdminSummaryBadge.vue'

const router = useRouter()
const { loading, begin, isLatest, end } = useLatestRequest()

const getAppCover = (cover?: string) => normalizeImageUrl(cover) || DEFAULT_APP_COVER
const getGenerationStateText = (app: API.OwnerAppVO) => {
  if (app.generatingStage === 'build') return '构建中'
  if (app.generatingStage === 'repair') return '修复中'
  if (app.generatingStage === 'agent') return '编排中'
  if (app.generatingStage === 'update') return '改修中'
  return '创建中'
}

const getGenerationStateClass = (app: API.OwnerAppVO) => {
  if (app.generatingStage === 'build') return 'is-build'
  if (app.generatingStage === 'repair') return 'is-repair'
  if (app.generatingStage === 'agent') return 'is-agent'
  return app.generatingStage === 'update' ? 'is-update' : 'is-create'
}

const columns = [
  { title: '应用信息', dataIndex: 'appInfo', width: 230, fixed: 'left' },
  { title: 'ID', dataIndex: 'id', width: 80 },
  { title: '封面', dataIndex: 'cover', width: 100 },
  { title: '初始提示词', dataIndex: 'initPrompt', width: 200 },
  { title: '生成类型', dataIndex: 'codeGenType', width: 100 },
  { title: '进度', dataIndex: 'generationState', width: 110 },
  { title: '优先级', dataIndex: 'priority', width: 80 },
  { title: '部署时间', dataIndex: 'deployedTime', width: 160 },
  { title: '创建者', dataIndex: 'user', width: 120 },
  { title: '创建时间', dataIndex: 'createTime', width: 160 },
  { title: '操作', key: 'action', width: 200, fixed: 'right' },
]

const data = ref<API.OwnerAppVO[]>([])
const total = ref(0)
const loadError = ref('')
const updatingFeaturedIds = ref<Set<number>>(new Set())
const deletingAppIds = ref<Set<number>>(new Set())
const searchParams = reactive<API.AppQueryRequest>({ pageNum: 1, pageSize: 10 })

const getAppRowKey = (record: API.OwnerAppVO) =>
  record.id ?? `${record.userId ?? 'user'}-${record.createTime ?? 'time'}`
const normalizeAppId = (value: string | number | undefined) => {
  const appId = Number(value)
  return Number.isSafeInteger(appId) && appId > 0 ? appId : null
}
const isUpdatingFeatured = (id?: string | number) => {
  const appId = normalizeAppId(id)
  return appId !== null && updatingFeaturedIds.value.has(appId)
}
const isDeletingApp = (id?: string | number) => {
  const appId = normalizeAppId(id)
  return appId !== null && deletingAppIds.value.has(appId)
}
const isAppBusy = (id?: string | number) => isUpdatingFeatured(id) || isDeletingApp(id)

const fetchData = async () => {
  const requestId = begin()
  loadError.value = ''
  try {
    const res = await listAppVoByPageByAdmin({ ...searchParams })
    if (!isLatest(requestId)) return

    if (res.data.code === 0 && res.data.data) {
      data.value = res.data.data.records ?? []
      total.value = res.data.data.totalRow ?? 0
      return
    }
    loadError.value = `获取应用列表失败：${res.data.message || '服务异常'}`
    message.error(loadError.value)
  } catch (error) {
    if (!isLatest(requestId)) return
    console.error('Failed to load apps', error)
    loadError.value = '获取应用列表失败，请检查网络后重试'
    message.error(loadError.value)
  } finally {
    end(requestId)
  }
}

const pagination = computed(() => ({
  current: searchParams.pageNum ?? 1,
  pageSize: searchParams.pageSize ?? 10,
  total: total.value,
  showSizeChanger: true,
  showTotal: (count: number) => `共 ${count} 条`,
}))

const doTableChange = (page: { current?: number; pageSize?: number }) => {
  searchParams.pageNum = page.current ?? 1
  searchParams.pageSize = page.pageSize ?? 10
  void fetchData()
}

const doSearch = () => {
  searchParams.pageNum = 1
  void fetchData()
}

const resetSearch = () => {
  searchParams.appName = undefined
  searchParams.userId = undefined
  searchParams.codeGenType = undefined
  searchParams.pageNum = 1
  void fetchData()
}

const editApp = (app: API.OwnerAppVO) => {
  const appId = normalizeAppId(app.id)
  if (!appId || isAppBusy(appId)) {
    if (!appId) message.warning('应用 ID 无效')
    return
  }
  void router.push({ name: 'app-edit', params: { id: String(appId) } })
}

const toggleFeatured = async (app: API.OwnerAppVO) => {
  const appId = normalizeAppId(app.id)
  if (!appId || isAppBusy(appId)) return

  const newPriority = app.priority === 99 ? 0 : 99
  updatingFeaturedIds.value = new Set(updatingFeaturedIds.value).add(appId)
  try {
    const res = await updateAppByAdmin({ id: appId, priority: newPriority })
    if (res.data.code !== 0) {
      message.error(`操作失败：${res.data.message || '服务异常'}`)
      return
    }
    message.success(newPriority === 99 ? '已设为精选' : '已取消精选')
    await fetchData()
  } catch (error) {
    console.error('Failed to update featured state', error)
    message.error('操作失败，请检查网络后重试')
  } finally {
    const nextIds = new Set(updatingFeaturedIds.value)
    nextIds.delete(appId)
    updatingFeaturedIds.value = nextIds
  }
}

const deleteApp = async (rawId?: string | number) => {
  const id = normalizeAppId(rawId)
  if (!id || isAppBusy(id)) return

  deletingAppIds.value = new Set(deletingAppIds.value).add(id)
  try {
    const res = await deleteAppByAdmin({ id })
    if (res.data.code !== 0) {
      message.error(`删除失败：${res.data.message || '服务异常'}`)
      return
    }
    if (data.value.length === 1 && (searchParams.pageNum ?? 1) > 1) {
      searchParams.pageNum = (searchParams.pageNum ?? 1) - 1
    }
    message.success('删除成功')
    await fetchData()
  } catch (error) {
    console.error('Failed to delete app', error)
    message.error('删除失败，请检查网络后重试')
  } finally {
    const nextIds = new Set(deletingAppIds.value)
    nextIds.delete(id)
    deletingAppIds.value = nextIds
  }
}

onMounted(() => {
  void fetchData()
})
</script>

<style scoped>
.app-info-cell {
  min-width: 0;
}

.app-name {
  overflow: hidden;
  color: var(--color-ink-strong);
  font-weight: 680;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.app-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 4px;
  color: #91a0b2;
  font-size: 11px;
}

.app-meta-divider {
  opacity: 0.55;
}

.prompt-text {
  max-width: 200px;
  overflow: hidden;
  color: #41556c;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.text-gray,
.generation-state-idle {
  color: #91a0b2;
}

.featured-btn {
  border-color: transparent;
  color: #fff;
  background: linear-gradient(135deg, #e9a521, #f4c44f);
  box-shadow: 0 8px 18px rgba(220, 155, 29, 0.18);
}

.featured-btn:hover,
.featured-btn:focus {
  border-color: transparent;
  color: #fff;
  background: linear-gradient(135deg, #d99213, #edb637);
}

.soft-tag {
  border: 0;
  border-radius: 999px;
}

.priority-text {
  color: #526579;
}

.action-button {
  border-radius: 10px;
}

.generation-state-chip {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 6px 11px;
  border: 1px solid rgba(148, 163, 184, 0.2);
  border-radius: 999px;
  background: rgba(248, 250, 252, 0.82);
  color: #526579;
  font-size: 12px;
  font-weight: 650;
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

.generation-state-chip.is-agent {
  background: rgba(238, 242, 255, 0.92);
  color: #4f46e5;
}

.generation-state-chip__dot {
  width: 6px;
  height: 6px;
  border-radius: 999px;
  background: currentColor;
  opacity: 0.8;
  animation: statePulse 1.9s ease-in-out infinite;
}

:deep(.cover-image .ant-image-img) {
  border-radius: 13px;
  object-fit: cover;
  box-shadow: 0 8px 20px rgba(63, 88, 120, 0.12);
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

@media (prefers-reduced-motion: reduce) {
  .generation-state-chip__dot {
    animation: none;
  }
}
</style>
