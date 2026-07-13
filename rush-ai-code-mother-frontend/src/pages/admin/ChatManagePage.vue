<template>
  <AdminPageFrame
    id="chatManagePage"
    eyebrow="CONVERSATION AUDIT"
    title="对话管理"
    description="从消息、应用和用户维度审视对话记录，快速回溯产品使用过程与异常上下文。"
  >
    <template #actions>
      <AdminSummaryBadge label="对话记录" :value="total" tone="slate" />
    </template>

    <AdminFilterPanel description="支持消息内容、角色、应用 ID 和用户 ID 组合查询。">
      <a-form class="toolbar-form" layout="inline" :model="searchParams" @finish="doSearch">
        <a-form-item label="消息内容">
          <a-input v-model:value="searchParams.message" allow-clear placeholder="输入消息内容" />
        </a-form-item>
        <a-form-item label="消息类型">
          <a-select
            v-model:value="searchParams.messageType"
            allow-clear
            placeholder="选择消息类型"
            style="width: 120px"
          >
            <a-select-option value="user">用户消息</a-select-option>
            <a-select-option value="assistant">AI 消息</a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item label="应用 ID">
          <a-input-number
            v-model:value="searchParams.appId"
            :min="1"
            :precision="0"
            placeholder="输入应用 ID"
          />
        </a-form-item>
        <a-form-item label="用户 ID">
          <a-input-number
            v-model:value="searchParams.userId"
            :min="1"
            :precision="0"
            placeholder="输入用户 ID"
          />
        </a-form-item>
        <a-form-item>
          <a-space>
            <a-button type="primary" html-type="submit" :loading="loading">搜索</a-button>
            <a-button :disabled="loading" @click="resetSearch">重置</a-button>
          </a-space>
        </a-form-item>
      </a-form>
    </AdminFilterPanel>

    <AdminDataPanel title="对话流水" description="查看消息归属、发起时间并直达对应应用工作台。">
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
        :row-key="getChatRowKey"
        :scroll="{ x: 1200 }"
        @change="doTableChange"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.dataIndex === 'message'">
            <a-tooltip :title="record.message">
              <div class="message-text">{{ record.message }}</div>
            </a-tooltip>
          </template>
          <template v-else-if="column.dataIndex === 'messageType'">
            <a-tag :color="record.messageType === 'user' ? 'blue' : 'green'">
              {{ record.messageType === 'user' ? '用户消息' : 'AI 消息' }}
            </a-tag>
          </template>
          <template v-else-if="column.dataIndex === 'createTime'">
            {{ formatTime(record.createTime) }}
          </template>
          <template v-else-if="column.key === 'action'">
            <a-button
              type="primary"
              size="small"
              :disabled="!record.appId"
              @click="viewAppChat(record.appId)"
            >
              查看对话
            </a-button>
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
import { listAllChatHistoryByPageForAdmin } from '@/api/chatHistoryController'
import { useLatestRequest } from '@/composables/useLatestRequest'
import { formatTime } from '@/utils/time'
import AdminPageFrame from '@/components/admin/AdminPageFrame.vue'
import AdminFilterPanel from '@/components/admin/AdminFilterPanel.vue'
import AdminDataPanel from '@/components/admin/AdminDataPanel.vue'
import AdminSummaryBadge from '@/components/admin/AdminSummaryBadge.vue'

const router = useRouter()
const { loading, begin, isLatest, end } = useLatestRequest()

const columns = [
  { title: 'ID', dataIndex: 'id', width: 80, fixed: 'left' },
  { title: '消息内容', dataIndex: 'message', width: 320 },
  { title: '消息类型', dataIndex: 'messageType', width: 110 },
  { title: '应用 ID', dataIndex: 'appId', width: 100 },
  { title: '用户 ID', dataIndex: 'userId', width: 100 },
  { title: '创建时间', dataIndex: 'createTime', width: 180 },
  { title: '操作', key: 'action', width: 110, fixed: 'right' },
]

const data = ref<API.ChatHistory[]>([])
const total = ref(0)
const loadError = ref('')
const searchParams = reactive<API.ChatHistoryQueryRequest>({
  pageNum: 1,
  pageSize: 10,
})

const getChatRowKey = (record: API.ChatHistory) =>
  record.id ?? `${record.appId ?? 'app'}-${record.userId ?? 'user'}-${record.createTime ?? 'time'}`

const fetchData = async () => {
  const requestId = begin()
  loadError.value = ''

  try {
    const res = await listAllChatHistoryByPageForAdmin({ ...searchParams })
    if (!isLatest(requestId)) return

    if (res.data.code === 0 && res.data.data) {
      data.value = res.data.data.records ?? []
      total.value = res.data.data.totalRow ?? 0
      return
    }

    loadError.value = `获取对话记录失败：${res.data.message || '服务异常'}`
    message.error(loadError.value)
  } catch (error) {
    if (!isLatest(requestId)) return
    console.error('Failed to load chat history', error)
    loadError.value = '获取对话记录失败，请检查网络后重试'
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
  searchParams.message = undefined
  searchParams.messageType = undefined
  searchParams.appId = undefined
  searchParams.userId = undefined
  searchParams.pageNum = 1
  void fetchData()
}

const viewAppChat = (appId?: number) => {
  if (!appId || !Number.isSafeInteger(appId) || appId <= 0) {
    message.warning('应用 ID 无效，无法查看对话')
    return
  }
  void router.push({ name: 'app-chat', params: { id: String(appId) } })
}

onMounted(() => {
  void fetchData()
})
</script>

<style scoped>
.message-text {
  max-width: 300px;
  overflow: hidden;
  color: #41556c;
  line-height: 1.55;
  text-overflow: ellipsis;
  white-space: nowrap;
}

:deep(.ant-tag) {
  border: 0;
  border-radius: 999px;
}

:deep(.ant-table .ant-btn) {
  border-radius: 10px;
}
</style>
