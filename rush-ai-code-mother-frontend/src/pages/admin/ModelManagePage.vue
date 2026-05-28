<template>
  <div id="modelManagePage" class="page-shell">
    <section class="page-head">
      <div>
        <h2 class="page-title">AI 模型管理</h2>
        <p class="page-desc">配置和管理 AI 模型，当前协议支持 OpenAI Chat Completions</p>
      </div>
      <div class="page-summary">
        <span class="summary-label">启用模型</span>
        <span class="summary-value">{{ enabledCount }}</span>
      </div>
    </section>

    <!-- 搜索和筛选 -->
    <a-card class="panel-card" :bordered="false">
      <a-form class="toolbar-form" layout="inline" :model="searchParams" @finish="doSearch">
        <a-form-item label="提供商">
          <a-select v-model:value="searchParams.provider" allow-clear placeholder="全部" style="width: 140px">
            <a-select-option value="deepseek">DeepSeek</a-select-option>
            <a-select-option value="openai">OpenAI</a-select-option>
            <a-select-option value="muskapi">MuskAPI</a-select-option>
            <a-select-option value="xiaomi">Xiaomi MiMo</a-select-option>
            <a-select-option value="custom">自定义</a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item label="模型类型">
          <a-select v-model:value="searchParams.modelType" allow-clear placeholder="全部" style="width: 140px">
            <a-select-option value="chat">对话模型</a-select-option>
            <a-select-option value="reasoning">推理模型</a-select-option>
            <a-select-option value="routing">路由模型</a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item label="关键词">
          <a-input v-model:value="searchParams.keyword" allow-clear placeholder="搜索模型名称/ID" />
        </a-form-item>
        <a-form-item>
          <a-space>
            <a-button type="primary" html-type="submit">搜索</a-button>
            <a-button @click="resetSearch">重置</a-button>
            <a-button type="primary" ghost @click="openAddModal">添加模型</a-button>
          </a-space>
        </a-form-item>
      </a-form>
    </a-card>

    <!-- 模型列表 -->
    <a-card class="panel-card table-card" :bordered="false">
      <a-table
        :columns="columns"
        :data-source="data"
        :pagination="pagination"
        :row-key="(record: API.AiModel) => record.id ?? ''"
        @change="doTableChange"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.dataIndex === 'modelInfo'">
            <div class="model-info-cell">
              <div class="model-icon" :class="getProviderClass(record.provider)">
                {{ getProviderIcon(record.provider) }}
              </div>
              <div class="model-info-text">
                <div class="model-name-row">
                  <span class="model-name">{{ record.modelName }}</span>
                  <a-tag :color="getProviderColor(record.provider)" class="soft-tag">
                    {{ getProviderLabel(record.provider) }}
                  </a-tag>
                </div>
                <div class="model-id">{{ record.modelId }}</div>
              </div>
            </div>
          </template>
          <template v-else-if="column.dataIndex === 'modelType'">
            <a-tag :color="getTypeColor(record.modelType)" class="soft-tag">
              {{ getTypeLabel(record.modelType) }}
            </a-tag>
          </template>
          <template v-else-if="column.dataIndex === 'supportsThinking'">
            <a-tag v-if="record.supportsThinking === 1" color="purple" class="soft-tag">
              支持
            </a-tag>
            <a-tag v-else color="default" class="soft-tag">
              不支持
            </a-tag>
          </template>
          <template v-else-if="column.dataIndex === 'isEnabled'">
            <a-switch
              :checked="record.isEnabled === 1"
              checked-children="启用"
              un-checked-children="禁用"
              @change="(checked: boolean) => handleToggleEnabled(record.id, checked)"
            />
          </template>
          <template v-else-if="column.dataIndex === 'maxTokens'">
            <span class="tokens-value">{{ formatTokens(record.maxTokens) }}</span>
          </template>
          <template v-else-if="column.dataIndex === 'baseUrl'">
            <a-tooltip :title="record.baseUrl">
              <span class="url-text">{{ truncateUrl(record.baseUrl) }}</span>
            </a-tooltip>
          </template>
          <template v-else-if="column.key === 'action'">
            <a-space>
              <a-button size="small" ghost class="action-button" @click="openEditModal(record)">
                编辑
              </a-button>
              <a-button size="small" ghost class="action-button" @click="handleTestConnection(record.id)">
                测试
              </a-button>
              <a-popconfirm title="确定要删除这个模型吗？" @confirm="handleDelete(record.id)">
                <a-button size="small" danger ghost class="action-button">删除</a-button>
              </a-popconfirm>
            </a-space>
          </template>
        </template>
      </a-table>
    </a-card>

    <!-- 添加/编辑模型弹窗 -->
    <a-modal
      v-model:open="modalVisible"
      :title="isEditing ? '编辑模型' : '添加模型'"
      @cancel="resetForm"
      width="640px"
    >
      <a-form
        ref="formRef"
        :model="formData"
        :rules="formRules"
        layout="vertical"
        class="model-form"
      >
        <a-row :gutter="16">
          <a-col :span="12">
            <a-form-item label="模型名称" name="modelName">
              <a-input v-model:value="formData.modelName" disabled placeholder="选择模型后自动填充" />
            </a-form-item>
          </a-col>
          <a-col :span="12">
            <a-form-item label="模型标识符" name="modelId">
              <a-select
                v-model:value="selectedModelKey"
                placeholder="选择系统支持的模型"
              >
                <a-select-option
                  v-for="model in supportedModels"
                  :key="`${model.provider}:${model.modelId}`"
                  :value="`${model.provider}:${model.modelId}`"
                >
                  {{ model.modelName }}（{{ model.providerLabel }}）
                </a-select-option>
              </a-select>
            </a-form-item>
          </a-col>
        </a-row>

        <a-row :gutter="16">
          <a-col :span="12">
            <a-form-item label="提供商" name="provider">
              <a-select v-model:value="formData.provider" disabled placeholder="选择模型后自动填充">
                <a-select-option v-for="provider in providerOptions" :key="provider.value" :value="provider.value">
                  {{ provider.label }}
                </a-select-option>
              </a-select>
            </a-form-item>
          </a-col>
          <a-col :span="12">
            <a-form-item label="模型类型" name="modelType">
              <a-select v-model:value="formData.modelType" placeholder="选择类型">
                <a-select-option v-for="type in currentSupportedTypes" :key="type" :value="type">
                  {{ getTypeLabel(type) }}
                </a-select-option>
              </a-select>
            </a-form-item>
          </a-col>
        </a-row>

        <a-form-item label="API 地址" name="baseUrl" extra="可只填写域名，例如 https://token-plan-cn.xiaomimimo.com，系统会按协议自动补 /v1。">
          <a-input
            v-model:value="formData.baseUrl"
            placeholder="https://token-plan-cn.xiaomimimo.com"
            @blur="normalizeFormBaseUrl"
          />
        </a-form-item>

        <a-form-item label="协议" name="protocol">
          <a-select v-model:value="formData.protocol" placeholder="请选择协议">
            <a-select-option value="openai_chat_completions">OpenAI Chat Completions</a-select-option>
          </a-select>
        </a-form-item>

        <a-form-item label="API 密钥" name="apiKey">
          <a-input-password v-model:value="formData.apiKey" placeholder="请输入 API 密钥" />
        </a-form-item>

        <a-row :gutter="16">
          <a-col :span="8">
            <a-form-item label="最大 Tokens" name="maxTokens">
              <a-input-number v-model:value="formData.maxTokens" :min="1" :max="1000000" style="width: 100%" />
            </a-form-item>
          </a-col>
          <a-col :span="8">
            <a-form-item label="温度" name="temperature">
              <a-input-number v-model:value="formData.temperature" :min="0" :max="2" :step="0.1" :precision="1" style="width: 100%" />
            </a-form-item>
          </a-col>
          <a-col :span="8">
            <a-form-item label="排序权重" name="sortOrder">
              <a-input-number v-model:value="formData.sortOrder" :min="0" style="width: 100%" />
            </a-form-item>
          </a-col>
        </a-row>

        <a-form-item label="支持 Thinking 模式" name="supportsThinking" help="由系统支持目录决定，不能手动修改">
          <a-switch
            v-model:checked="formData.supportsThinking"
            :checked-value="1"
            :un-checked-value="0"
            disabled
            checked-children="支持"
            un-checked-children="不支持"
          />
          <span v-if="formData.supportsThinking" style="margin-left: 12px; color: #52c41a;">
            ✓ 该模型将启用 thinking 模式
          </span>
        </a-form-item>

        <a-form-item label="描述" name="description">
          <a-textarea v-model:value="formData.description" :rows="2" placeholder="请输入模型描述" />
        </a-form-item>

        <a-form-item label="启用状态">
          <a-switch v-model:checked="formData.isEnabled" checked-children="启用" un-checked-children="禁用" />
        </a-form-item>
      </a-form>
      <template #footer>
        <a-space>
          <a-button @click="resetForm">取消</a-button>
          <a-button :loading="testingConfig" @click="handleTestCurrentConfig">测试当前配置</a-button>
          <a-button type="primary" :loading="submitting" @click="handleSubmit">
            {{ isEditing ? '更新' : '添加' }}
          </a-button>
        </a-space>
      </template>
    </a-modal>
  </div>
</template>

<script lang="ts" setup>
import { computed, onMounted, reactive, ref } from 'vue'
import {
  addModel,
  deleteModel,
  listSupportedModels,
  listModelsByPage,
  testModelConnection,
  testModelConnectionByConfig,
  toggleModelEnabled,
  updateModel,
} from '@/api/aiModelController.ts'
import { message } from 'ant-design-vue'
import type { FormInstance } from 'ant-design-vue'

// 表格列定义
const columns = [
  {
    title: '模型信息',
    dataIndex: 'modelInfo',
    width: 280,
  },
  {
    title: '类型',
    dataIndex: 'modelType',
    width: 100,
  },
  {
    title: 'Thinking',
    dataIndex: 'supportsThinking',
    width: 100,
  },
  {
    title: '状态',
    dataIndex: 'isEnabled',
    width: 100,
  },
  {
    title: '最大 Tokens',
    dataIndex: 'maxTokens',
    width: 120,
  },
  {
    title: 'API 地址',
    dataIndex: 'baseUrl',
    width: 200,
  },
  {
    title: '操作',
    key: 'action',
    width: 200,
  },
]

// 展示的数据
const data = ref<API.AiModel[]>([])
const total = ref(0)
const enabledCount = ref(0)
const modalVisible = ref(false)
const isEditing = ref(false)
const submitting = ref(false)
const testingConfig = ref(false)
const formRef = ref<FormInstance>()
const supportedModels = ref<API.SupportedAiModelVO[]>([])

// 搜索条件
const searchParams = reactive<API.AiModelQueryRequest>({
  pageNum: 1,
  pageSize: 10,
  provider: undefined,
  modelType: undefined,
  keyword: undefined,
})

const providerOptions = computed(() => {
  const providerMap = new Map<string, string>()
  supportedModels.value.forEach((model) => {
    if (model.provider) {
      providerMap.set(model.provider, model.providerLabel ?? model.provider)
    }
  })
  return Array.from(providerMap.entries()).map(([value, label]) => ({ value, label }))
})

// 表单数据
const formData = reactive<API.AiModelAddRequest & { id?: number }>({
  id: undefined,
  modelName: '',
  provider: 'deepseek',
  modelId: '',
  description: '',
  baseUrl: '',
  apiKey: '',
  maxTokens: 8192,
  temperature: 0.7,
  isEnabled: 1,
  modelType: 'chat',
  supportsThinking: 0,
  sortOrder: 0,
  protocol: 'openai_chat_completions',
})

// 表单验证规则
const formRules = {
  modelName: [{ required: true, message: '请输入模型名称' }],
  provider: [{ required: true, message: '请选择提供商' }],
  modelId: [{ required: true, message: '请选择模型' }],
  baseUrl: [{ required: true, message: '请输入 API 地址' }],
  protocol: [{ required: true, message: '请选择协议' }],
  modelType: [{ required: true, message: '请选择模型类型' }],
}

const currentCatalogModel = computed(() =>
  supportedModels.value.find(
    (model) => model.provider === formData.provider && model.modelId === formData.modelId,
  ),
)

const currentSupportedTypes = computed(() => currentCatalogModel.value?.supportedModelTypes ?? ['chat'])

const selectedModelKey = computed({
  get() {
    if (!formData.provider || !formData.modelId) {
      return undefined
    }
    return `${formData.provider}:${formData.modelId}`
  },
  set(modelKey: string | undefined) {
    if (modelKey) {
      handleModelSelect(modelKey)
    }
  },
})

const fetchCatalog = async () => {
  const res = await listSupportedModels()
  if (res.data.code === 0) {
    supportedModels.value = res.data.data ?? []
    if (!formData.modelId && supportedModels.value.length > 0) {
      applyCatalogModel(supportedModels.value[0])
    }
  } else {
    message.error('获取支持模型目录失败，' + res.data.message)
  }
}

// 获取数据
const fetchData = async () => {
  const res = await listModelsByPage({ ...searchParams })
  if (res.data.data) {
    data.value = res.data.data.records ?? []
    total.value = res.data.data.totalRow ?? 0
    enabledCount.value = data.value.filter((m) => m.isEnabled === 1).length
  } else {
    message.error('获取数据失败，' + res.data.message)
  }
}

// 分页参数
const pagination = computed(() => ({
  current: searchParams.pageNum ?? 1,
  pageSize: searchParams.pageSize ?? 10,
  total: total.value,
  showSizeChanger: true,
  showTotal: (total: number) => `共 ${total} 条`,
}))

// 表格分页变化
const doTableChange = (page: { current: number; pageSize: number }) => {
  searchParams.pageNum = page.current
  searchParams.pageSize = page.pageSize
  fetchData()
}

// 搜索
const doSearch = () => {
  searchParams.pageNum = 1
  fetchData()
}

// 重置搜索
const resetSearch = () => {
  searchParams.provider = undefined
  searchParams.modelType = undefined
  searchParams.keyword = undefined
  searchParams.pageNum = 1
  fetchData()
}

// 打开添加弹窗
const openAddModal = () => {
  isEditing.value = false
  resetForm()
  modalVisible.value = true
}

// 打开编辑弹窗
const openEditModal = (record: API.AiModel) => {
  isEditing.value = true
  const catalogModel = supportedModels.value.find(
    (model) => model.provider === record.provider && model.modelId === record.modelId,
  )
  Object.assign(formData, {
    id: record.id,
    modelName: catalogModel?.modelName ?? record.modelName,
    provider: catalogModel?.provider ?? record.provider,
    modelId: catalogModel?.modelId ?? record.modelId,
    description: record.description,
    baseUrl: record.baseUrl ?? catalogModel?.defaultBaseUrl,
    apiKey: record.apiKey,
    maxTokens: record.maxTokens ?? catalogModel?.defaultMaxTokens,
    temperature: record.temperature ?? catalogModel?.defaultTemperature,
    isEnabled: record.isEnabled === 1,
    modelType: record.modelType ?? catalogModel?.defaultModelType,
    supportsThinking: catalogModel?.supportsThinking ?? record.supportsThinking ?? 0,
    sortOrder: record.sortOrder,
    protocol: getProtocolFromConfig(record.configJson) ?? catalogModel?.defaultProtocol ?? 'openai_chat_completions',
  })
  modalVisible.value = true
}

const handleModelSelect = (modelKey: string) => {
  const [provider, modelId] = modelKey.split(':')
  const selected = supportedModels.value.find(
    (model) => model.provider === provider && model.modelId === modelId,
  )
  if (selected) {
    applyCatalogModel(selected)
  }
}

const applyCatalogModel = (model: API.SupportedAiModelVO) => {
  Object.assign(formData, {
    modelName: model.modelName ?? '',
    provider: model.provider ?? '',
    modelId: model.modelId ?? '',
    baseUrl: model.defaultBaseUrl ?? '',
    maxTokens: model.defaultMaxTokens ?? 8192,
    temperature: model.defaultTemperature ?? 0.7,
    modelType: model.defaultModelType ?? model.supportedModelTypes?.[0] ?? 'chat',
    supportsThinking: model.supportsThinking ?? 0,
    protocol: model.defaultProtocol ?? 'openai_chat_completions',
  })
}

// 重置表单
const resetForm = () => {
  Object.assign(formData, {
    id: undefined,
    modelName: '',
    provider: 'deepseek',
    modelId: '',
    description: '',
    baseUrl: '',
    apiKey: '',
    maxTokens: 8192,
    temperature: 0.7,
    isEnabled: true,
    modelType: 'chat',
    supportsThinking: 0,
    sortOrder: 0,
    protocol: 'openai_chat_completions',
  })
  if (supportedModels.value.length > 0) {
    applyCatalogModel(supportedModels.value[0])
  }
}

// 提交表单
const handleSubmit = async () => {
  try {
    await formRef.value?.validateFields()
  } catch {
    return
  }

  submitting.value = true
  try {
    normalizeFormBaseUrl()
    const submitData = {
      ...formData,
      isEnabled: formData.isEnabled ? 1 : 0,
    }

    if (isEditing.value && formData.id) {
      const res = await updateModel(submitData as API.AiModelUpdateRequest)
      if (res.data.code === 0) {
        message.success('更新成功')
        modalVisible.value = false
        fetchData()
      } else {
        message.error('更新失败，' + res.data.message)
      }
    } else {
      const res = await addModel(submitData as API.AiModelAddRequest)
      if (res.data.code === 0) {
        message.success('添加成功')
        modalVisible.value = false
        fetchData()
      } else {
        message.error('添加失败，' + res.data.message)
      }
    }
  } finally {
    submitting.value = false
  }
}

// 切换启用状态
const handleToggleEnabled = async (id: number | undefined, checked: boolean) => {
  if (!id) return
  const res = await toggleModelEnabled({ id })
  if (res.data.code === 0) {
    message.success(checked ? '已启用' : '已禁用')
    fetchData()
  } else {
    message.error('操作失败')
  }
}

// 测试连接
const handleTestConnection = async (id: number | undefined) => {
  if (!id) return
  const hide = message.loading('正在测试连接...', 0)
  try {
    const res = await testModelConnection({ id })
    if (res.data.code === 0) {
      message.success('连接测试成功')
    } else {
      message.error('连接测试失败，请检查配置')
    }
  } finally {
    hide()
  }
}

const handleTestCurrentConfig = async () => {
  try {
    normalizeFormBaseUrl()
    await formRef.value?.validateFields()
  } catch {
    return
  }
  testingConfig.value = true
  try {
    const res = await testModelConnectionByConfig({
      ...formData,
      isEnabled: formData.isEnabled ? 1 : 0,
    } as API.AiModelAddRequest)
    if (res.data.code === 0) {
      message.success(res.data.data?.message ?? '连接测试成功')
    } else {
      message.error(res.data.message ?? '连接测试失败')
    }
  } finally {
    testingConfig.value = false
  }
}

// 删除模型
const handleDelete = async (id: number | undefined) => {
  if (!id) return
  const res = await deleteModel({ id })
  if (res.data.code === 0) {
    message.success('删除成功')
    fetchData()
  } else {
    message.error('删除失败')
  }
}

// 工具函数
const getProviderClass = (provider?: string) => {
  const map: Record<string, string> = {
    deepseek: 'provider-deepseek',
    openai: 'provider-openai',
    muskapi: 'provider-custom',
    xiaomi: 'provider-xiaomi',
  }
  return map[provider ?? ''] ?? 'provider-custom'
}

const getProviderIcon = (provider?: string) => {
  const map: Record<string, string> = {
    deepseek: 'DS',
    openai: 'AI',
    muskapi: 'MK',
    xiaomi: 'MI',
    custom: 'CM',
  }
  return map[provider ?? ''] ?? 'CM'
}

const getProviderLabel = (provider?: string) => {
  const map: Record<string, string> = {
    deepseek: 'DeepSeek',
    openai: 'OpenAI',
    muskapi: 'MuskAPI',
    xiaomi: 'Xiaomi MiMo',
    custom: '自定义',
  }
  return map[provider ?? ''] ?? provider
}

const getProviderColor = (provider?: string) => {
  const map: Record<string, string> = {
    deepseek: 'blue',
    openai: 'green',
    muskapi: 'orange',
    xiaomi: 'red',
    custom: 'default',
  }
  return map[provider ?? ''] ?? 'default'
}

const getTypeLabel = (type?: string) => {
  const map: Record<string, string> = {
    chat: '对话',
    reasoning: '推理',
    routing: '路由',
  }
  return map[type ?? ''] ?? type
}

const getTypeColor = (type?: string) => {
  const map: Record<string, string> = {
    chat: 'blue',
    reasoning: 'purple',
    routing: 'cyan',
  }
  return map[type ?? ''] ?? 'default'
}

const formatTokens = (tokens?: number) => {
  if (!tokens) return '-'
  if (tokens >= 1000000) return `${(tokens / 1000000).toFixed(0)}M`
  if (tokens >= 1000) return `${(tokens / 1000).toFixed(0)}K`
  return tokens.toString()
}

const truncateUrl = (url?: string) => {
  if (!url) return '-'
  try {
    const parsed = new URL(url)
    return parsed.hostname
  } catch {
    return url.length > 30 ? url.slice(0, 30) + '...' : url
  }
}

const normalizeFormBaseUrl = () => {
  formData.baseUrl = normalizeOpenAiBaseUrl(formData.baseUrl)
}

const normalizeOpenAiBaseUrl = (value?: string) => {
  const trimmed = value?.trim()
  if (!trimmed) {
    return ''
  }
  try {
    const url = new URL(trimmed)
    const path = url.pathname.replace(/\/$/, '')
    if (!path) {
      url.pathname = '/v1'
    } else if (path === '/chat/completions') {
      url.pathname = '/v1'
    } else if (path.endsWith('/chat/completions')) {
      url.pathname = path.slice(0, -'/chat/completions'.length) || '/v1'
    }
    url.search = ''
    url.hash = ''
    return url.toString().replace(/\/$/, '')
  } catch {
    return trimmed.replace(/\/$/, '')
  }
}

const getProtocolFromConfig = (configJson?: string) => {
  if (!configJson) {
    return undefined
  }
  try {
    const config = JSON.parse(configJson)
    return typeof config.protocol === 'string' ? config.protocol : undefined
  } catch {
    return undefined
  }
}

// 页面加载
onMounted(() => {
  fetchCatalog()
  fetchData()
})
</script>

<style scoped>
#modelManagePage {
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

.page-desc {
  margin: 4px 0 0;
  font-size: 14px;
  color: #64748b;
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
  gap: 10px 8px;
  align-items: center;
}

.panel-card :deep(.ant-card-body) {
  padding: 18px 20px;
}

.table-card :deep(.ant-card-body) {
  padding-top: 6px;
}

.table-card :deep(.ant-table) {
  background: transparent;
}

.table-card :deep(.ant-table-container) {
  border-radius: 14px;
  overflow: hidden;
}

.table-card :deep(.ant-table-thead > tr > th) {
  background: #f8fafc;
  color: #475569;
  font-weight: 600;
  border-bottom: 1px solid rgba(148, 163, 184, 0.16);
  padding-block: 14px;
}

.table-card :deep(.ant-table-tbody > tr > td) {
  border-bottom: 1px solid rgba(226, 232, 240, 0.8);
  vertical-align: middle;
  padding-block: 14px;
}

.table-card :deep(.ant-table-tbody > tr:hover > td) {
  background: rgba(59, 130, 246, 0.035);
}

.model-info-cell {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}

.model-icon {
  flex: 0 0 auto;
  width: 40px;
  height: 40px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 700;
  font-size: 14px;
  color: white;
}

.provider-deepseek {
  background: linear-gradient(135deg, #4f46e5, #7c3aed);
}

.provider-openai {
  background: linear-gradient(135deg, #059669, #10b981);
}

.provider-custom {
  background: linear-gradient(135deg, #d97706, #f59e0b);
}

.provider-xiaomi {
  background: linear-gradient(135deg, #dc2626, #f97316);
}

.model-info-text {
  min-width: 0;
}

.model-name-row {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.model-name {
  font-weight: 600;
  color: #0f172a;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.model-id {
  margin-top: 2px;
  color: #94a3b8;
  font-size: 12px;
  font-family: monospace;
}

.tokens-value {
  font-family: monospace;
  color: #475569;
}

.url-text {
  font-size: 12px;
  color: #64748b;
  max-width: 180px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  display: inline-block;
}

.soft-tag {
  border-radius: 999px;
  border: 0;
}

.action-button {
  border-radius: 999px;
}

.model-form {
  margin-top: 16px;
}

:deep(.ant-input),
:deep(.ant-select-selector),
:deep(.ant-btn),
:deep(.ant-input-number) {
  border-radius: 8px !important;
}

:deep(.ant-form-item) {
  margin-bottom: 16px;
}

@media (max-width: 768px) {
  #modelManagePage {
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
