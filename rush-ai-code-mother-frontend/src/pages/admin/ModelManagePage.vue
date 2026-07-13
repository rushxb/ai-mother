<template>
  <AdminPageFrame
    id="modelManagePage"
    eyebrow="MODEL INFRASTRUCTURE"
    title="AI 模型管理"
    description="统一管理模型目录、连接配置和路由角色，保证生成链路始终具备可用的快速模型与思考模型。"
  >
    <template #actions>
      <AdminSummaryBadge
        label="快速模型"
        :value="getEnabledModelStatus(hasFastModel)"
        :tone="enabledModelsError ? 'warning' : hasFastModel ? 'success' : 'danger'"
        :title="enabledModelsError"
      />
      <AdminSummaryBadge
        label="思考模型"
        :value="getEnabledModelStatus(hasThinkingModel)"
        :tone="enabledModelsError ? 'warning' : hasThinkingModel ? 'success' : 'danger'"
        :title="enabledModelsError"
      />
    </template>

    <AdminFilterPanel description="按提供商、模型类型与关键词组合检索，也可从支持目录中新增模型。">
      <a-alert
        v-if="catalogError"
        type="warning"
        show-icon
        :message="catalogError"
        class="load-error"
      >
        <template #action>
          <a-button size="small" :loading="catalogLoading" @click="fetchCatalog">重试加载</a-button>
        </template>
      </a-alert>
      <a-form class="toolbar-form" layout="inline" :model="searchParams" @finish="doSearch">
        <a-form-item label="提供商">
          <a-select
            v-model:value="searchParams.provider"
            allow-clear
            placeholder="全部"
            style="width: 140px"
          >
            <a-select-option
              v-for="provider in providerOptions"
              :key="provider.value"
              :value="provider.value"
            >
              {{ provider.label }}
            </a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item label="模型类型">
          <a-select
            v-model:value="searchParams.modelType"
            allow-clear
            placeholder="全部"
            style="width: 140px"
          >
            <a-select-option value="chat">快速模型</a-select-option>
            <a-select-option value="reasoning">思考模型</a-select-option>
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
            <a-button
              type="primary"
              ghost
              :loading="catalogLoading"
              :disabled="supportedModels.length === 0"
              @click="openAddModal"
            >
              添加模型
            </a-button>
          </a-space>
        </a-form-item>
      </a-form>
    </AdminFilterPanel>

    <AdminDataPanel
      title="模型目录"
      description="监控提供商、模型角色、Thinking 能力与当前启用状态。"
    >
      <a-alert
        v-if="loadError"
        type="error"
        show-icon
        closable
        :message="loadError"
        class="load-error"
        @close="loadError = ''"
      >
        <template #action>
          <a-button size="small" @click="fetchData">重试</a-button>
        </template>
      </a-alert>
      <a-table
        :columns="columns"
        :data-source="data"
        :loading="loading"
        :pagination="pagination"
        :scroll="{ x: 1000 }"
        :row-key="getModelRowKey"
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
            <a-tag v-else color="default" class="soft-tag"> 不支持 </a-tag>
          </template>
          <template v-else-if="column.dataIndex === 'isEnabled'">
            <a-switch
              :checked="record.isEnabled === 1"
              :loading="isTogglingModel(record.id)"
              :disabled="isModelBusy(record.id)"
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
              <a-button
                type="primary"
                size="small"
                class="action-button"
                :disabled="isModelBusy(record.id)"
                @click="openEditModal(record)"
              >
                编辑
              </a-button>
              <a-button
                size="small"
                class="action-button secondary-action-button"
                :loading="isTestingModel(record.id)"
                :disabled="isModelBusy(record.id)"
                @click="handleTestConnection(record.id)"
              >
                测试
              </a-button>
              <a-popconfirm
                title="确定要删除这个模型吗？"
                :disabled="isModelBusy(record.id)"
                @confirm="handleDelete(record.id)"
              >
                <a-button
                  size="small"
                  danger
                  ghost
                  class="action-button"
                  :loading="isDeletingModel(record.id)"
                  :disabled="isModelBusy(record.id)"
                >
                  删除
                </a-button>
              </a-popconfirm>
            </a-space>
          </template>
        </template>
      </a-table>
    </AdminDataPanel>

    <!-- 添加/编辑模型弹窗 -->
    <a-modal
      v-model:open="modalVisible"
      :title="isEditing ? '编辑模型' : '添加模型'"
      :closable="!submitting && !testingConfig"
      :keyboard="!submitting && !testingConfig"
      :mask-closable="!submitting && !testingConfig"
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
              <a-input
                v-model:value="formData.modelName"
                disabled
                placeholder="选择模型后自动填充"
              />
            </a-form-item>
          </a-col>
          <a-col :span="12">
            <a-form-item label="模型标识符" name="modelId">
              <a-select v-model:value="selectedModelKey" placeholder="选择系统支持的模型">
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
                <a-select-option
                  v-for="provider in providerOptions"
                  :key="provider.value"
                  :value="provider.value"
                >
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

        <a-form-item
          label="API 地址"
          name="baseUrl"
          extra="可只填写域名，例如 https://token-plan-cn.xiaomimimo.com，系统会按协议自动补 /v1。"
        >
          <a-input
            v-model:value="formData.baseUrl"
            placeholder="https://token-plan-cn.xiaomimimo.com"
            @blur="normalizeFormBaseUrl"
          />
        </a-form-item>

        <a-form-item label="协议" name="protocol">
          <a-select v-model:value="formData.protocol" placeholder="请选择协议">
            <a-select-option value="openai_chat_completions"
              >OpenAI Chat Completions</a-select-option
            >
          </a-select>
        </a-form-item>

        <a-form-item label="API 密钥" name="apiKey" :extra="apiKeyHelpText">
          <a-input-password
            v-model:value="formData.apiKey"
            :placeholder="isEditing ? '留空保留已配置密钥' : '请输入 API 密钥'"
            autocomplete="new-password"
          />
        </a-form-item>

        <a-row :gutter="16">
          <a-col :span="8">
            <a-form-item label="最大 Tokens" name="maxTokens">
              <a-input-number
                v-model:value="formData.maxTokens"
                :min="1"
                :max="1000000"
                style="width: 100%"
              />
            </a-form-item>
          </a-col>
          <a-col :span="8">
            <a-form-item label="温度" name="temperature">
              <a-input-number
                v-model:value="formData.temperature"
                :min="0"
                :max="2"
                :step="0.1"
                :precision="1"
                style="width: 100%"
              />
            </a-form-item>
          </a-col>
          <a-col :span="8">
            <a-form-item label="排序权重" name="sortOrder">
              <a-input-number v-model:value="formData.sortOrder" :min="0" style="width: 100%" />
            </a-form-item>
          </a-col>
        </a-row>

        <a-form-item
          label="支持 Thinking 模式"
          name="supportsThinking"
          help="由系统支持目录决定，不能手动修改"
        >
          <a-switch
            v-model:checked="formData.supportsThinking"
            :checked-value="1"
            :un-checked-value="0"
            disabled
            checked-children="支持"
            un-checked-children="不支持"
          />
          <span v-if="formData.supportsThinking" style="margin-left: 12px; color: #52c41a">
            ✓ 该模型将启用 thinking 模式
          </span>
        </a-form-item>

        <a-form-item label="描述" name="description">
          <a-textarea v-model:value="formData.description" :rows="2" placeholder="请输入模型描述" />
        </a-form-item>

        <a-form-item label="启用状态">
          <a-switch
            v-model:checked="formData.isEnabled"
            checked-children="启用"
            un-checked-children="禁用"
          />
        </a-form-item>
      </a-form>
      <template #footer>
        <a-space>
          <a-button :disabled="submitting || testingConfig" @click="closeModal">取消</a-button>
          <a-button
            :loading="testingConfig"
            :disabled="submitting"
            @click="handleTestCurrentConfig"
          >
            测试当前配置
          </a-button>
          <a-button
            type="primary"
            :loading="submitting"
            :disabled="testingConfig"
            @click="handleSubmit"
          >
            {{ isEditing ? '更新' : '添加' }}
          </a-button>
        </a-space>
      </template>
    </a-modal>
  </AdminPageFrame>
</template>

<script lang="ts" setup>
import { computed, onMounted, reactive, ref } from 'vue'
import type { Ref } from 'vue'
import {
  addModel,
  deleteModel,
  listEnabledModels,
  listSupportedModels,
  listModelsByPage,
  testModelConnection,
  testModelConnectionByConfig,
  toggleModelEnabled,
  updateModel,
} from '@/api/aiModelController.ts'
import { message } from 'ant-design-vue'
import type { FormInstance } from 'ant-design-vue'
import { useLatestRequest } from '@/composables/useLatestRequest'
import AdminPageFrame from '@/components/admin/AdminPageFrame.vue'
import AdminFilterPanel from '@/components/admin/AdminFilterPanel.vue'
import AdminDataPanel from '@/components/admin/AdminDataPanel.vue'
import AdminSummaryBadge from '@/components/admin/AdminSummaryBadge.vue'

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
const enabledModels = ref<API.AiModel[]>([])
const total = ref(0)
const loadError = ref('')
const catalogError = ref('')
const enabledModelsError = ref('')
const modalVisible = ref(false)
const isEditing = ref(false)
const editingModelHasApiKey = ref(false)
const submitting = ref(false)
const testingConfig = ref(false)
const formRef = ref<FormInstance>()
const supportedModels = ref<API.SupportedAiModelVO[]>([])
const togglingModelIds = ref<Set<number>>(new Set())
const testingModelIds = ref<Set<number>>(new Set())
const deletingModelIds = ref<Set<number>>(new Set())
const { loading, begin, isLatest, end } = useLatestRequest()
const {
  loading: catalogLoading,
  begin: beginCatalogRequest,
  isLatest: isLatestCatalogRequest,
  end: endCatalogRequest,
} = useLatestRequest()
const {
  loading: enabledModelsLoading,
  begin: beginEnabledModelsRequest,
  isLatest: isLatestEnabledModelsRequest,
  end: endEnabledModelsRequest,
} = useLatestRequest()

type ModelFormData = Omit<API.AiModelAddRequest, 'isEnabled'> & {
  id?: number
  isEnabled: boolean
}

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
const formData = reactive<ModelFormData>({
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

const validateBaseUrl = async (_rule: unknown, value?: string) => {
  const normalizedUrl = normalizeOpenAiBaseUrl(value)
  if (!normalizedUrl) {
    return Promise.reject(new Error('请输入 API 地址'))
  }
  try {
    const parsedUrl = new URL(normalizedUrl)
    if (!['http:', 'https:'].includes(parsedUrl.protocol) || !parsedUrl.hostname) {
      throw new Error('unsupported protocol')
    }
  } catch {
    return Promise.reject(new Error('请输入有效的 HTTP/HTTPS API 地址'))
  }
  return Promise.resolve()
}

const validateApiKey = async (_rule: unknown, value?: string) => {
  if ((!isEditing.value || !editingModelHasApiKey.value) && !value?.trim()) {
    return Promise.reject(
      new Error(
        isEditing.value ? '当前模型未配置密钥，请输入 API 密钥' : '新增模型时必须输入 API 密钥',
      ),
    )
  }
  return Promise.resolve()
}

// 表单验证规则
const formRules = {
  modelName: [{ required: true, message: '请输入模型名称' }],
  provider: [{ required: true, message: '请选择提供商' }],
  modelId: [{ required: true, message: '请选择模型' }],
  baseUrl: [{ validator: validateBaseUrl, trigger: 'blur' }],
  apiKey: [{ validator: validateApiKey, trigger: 'blur' }],
  protocol: [{ required: true, message: '请选择协议' }],
  modelType: [{ required: true, message: '请选择模型类型' }],
}

const currentCatalogModel = computed(() =>
  supportedModels.value.find(
    (model) => model.provider === formData.provider && model.modelId === formData.modelId,
  ),
)

const currentSupportedTypes = computed(
  () => currentCatalogModel.value?.supportedModelTypes ?? ['chat'],
)

const hasFastModel = computed(() => enabledModels.value.some((model) => model.modelType === 'chat'))

const hasThinkingModel = computed(() =>
  enabledModels.value.some((model) => model.modelType === 'reasoning'),
)

const apiKeyHelpText = computed(() => {
  if (!isEditing.value) {
    return '密钥只用于服务端调用，保存后不会回显明文。'
  }
  return editingModelHasApiKey.value
    ? '已配置密钥；留空将保留原密钥，输入新值才会替换。'
    : '当前未配置密钥，请输入新密钥。'
})

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

const normalizeModelId = (value?: number) =>
  Number.isSafeInteger(value) && Number(value) > 0 ? Number(value) : null

const getModelRowKey = (record: API.AiModel) =>
  record.id ?? `${record.provider ?? 'provider'}-${record.modelId ?? 'model'}`

const isTogglingModel = (id?: number) => {
  const modelId = normalizeModelId(id)
  return modelId !== null && togglingModelIds.value.has(modelId)
}

const isTestingModel = (id?: number) => {
  const modelId = normalizeModelId(id)
  return modelId !== null && testingModelIds.value.has(modelId)
}

const isDeletingModel = (id?: number) => {
  const modelId = normalizeModelId(id)
  return modelId !== null && deletingModelIds.value.has(modelId)
}

const isModelBusy = (id?: number) =>
  isTogglingModel(id) || isTestingModel(id) || isDeletingModel(id)

const withModelLock = async (
  lockedIds: Ref<Set<number>>,
  modelId: number,
  action: () => Promise<void>,
) => {
  if (lockedIds.value.has(modelId)) return
  lockedIds.value = new Set(lockedIds.value).add(modelId)
  try {
    await action()
  } finally {
    const nextIds = new Set(lockedIds.value)
    nextIds.delete(modelId)
    lockedIds.value = nextIds
  }
}

const getEnabledModelStatus = (configured: boolean) => {
  if (enabledModelsLoading.value) return '加载中'
  if (enabledModelsError.value) return '加载失败'
  return configured ? '已配置' : '未配置'
}

const fetchCatalog = async () => {
  const requestId = beginCatalogRequest()
  catalogError.value = ''
  try {
    const res = await listSupportedModels()
    if (!isLatestCatalogRequest(requestId)) return

    if (res.data.code !== 0) {
      catalogError.value = `获取支持模型目录失败：${res.data.message || '服务异常'}`
      message.error(catalogError.value)
      return
    }
    supportedModels.value = res.data.data ?? []
    if (!formData.modelId && supportedModels.value.length > 0) {
      applyCatalogModel(supportedModels.value[0])
    }
  } catch (error) {
    if (!isLatestCatalogRequest(requestId)) return
    console.error('Failed to load supported model catalog', error)
    catalogError.value = '获取支持模型目录失败，请检查网络后重试'
    message.error(catalogError.value)
  } finally {
    endCatalogRequest(requestId)
  }
}

// 获取分页数据
const fetchData = async () => {
  const requestId = begin()
  loadError.value = ''
  try {
    const res = await listModelsByPage({ ...searchParams })
    if (!isLatest(requestId)) return

    if (res.data.code === 0 && res.data.data) {
      data.value = res.data.data.records ?? []
      total.value = res.data.data.totalRow ?? 0
      return
    }
    loadError.value = `获取模型列表失败：${res.data.message || '服务异常'}`
    message.error(loadError.value)
  } catch (error) {
    if (!isLatest(requestId)) return
    console.error('Failed to load models', error)
    loadError.value = '获取模型列表失败，请检查网络后重试'
    message.error(loadError.value)
  } finally {
    end(requestId)
  }
}

const fetchEnabledModels = async () => {
  const requestId = beginEnabledModelsRequest()
  enabledModelsError.value = ''
  try {
    const res = await listEnabledModels()
    if (!isLatestEnabledModelsRequest(requestId)) return

    if (res.data.code === 0) {
      enabledModels.value = res.data.data ?? []
      return
    }
    enabledModelsError.value = res.data.message || '服务异常'
  } catch (error) {
    if (!isLatestEnabledModelsRequest(requestId)) return
    console.error('Failed to load enabled models', error)
    enabledModelsError.value = '请检查网络后重试'
  } finally {
    endEnabledModelsRequest(requestId)
  }
}

const refreshModelState = async () => {
  await Promise.all([fetchData(), fetchEnabledModels()])
}

// 分页参数
const pagination = computed(() => ({
  current: searchParams.pageNum ?? 1,
  pageSize: searchParams.pageSize ?? 10,
  total: total.value,
  showSizeChanger: true,
  showTotal: (count: number) => `共 ${count} 条`,
}))

// 表格分页变化
const doTableChange = (page: { current?: number; pageSize?: number }) => {
  searchParams.pageNum = page.current ?? 1
  searchParams.pageSize = page.pageSize ?? 10
  void fetchData()
}

// 搜索
const doSearch = () => {
  searchParams.pageNum = 1
  void fetchData()
}

// 重置搜索
const resetSearch = () => {
  searchParams.provider = undefined
  searchParams.modelType = undefined
  searchParams.keyword = undefined
  searchParams.pageNum = 1
  void fetchData()
}

// 打开添加弹窗
const openAddModal = () => {
  if (supportedModels.value.length === 0) {
    message.warning('支持模型目录尚未加载，请稍后重试')
    return
  }
  isEditing.value = false
  resetForm()
  modalVisible.value = true
}

// 打开编辑弹窗
const openEditModal = (record: API.AiModel) => {
  const modelId = normalizeModelId(record.id)
  if (!modelId || isModelBusy(modelId)) {
    if (!modelId) message.warning('模型 ID 无效')
    return
  }

  isEditing.value = true
  editingModelHasApiKey.value = Boolean(record.apiKeyConfigured)
  const catalogModel = supportedModels.value.find(
    (model) => model.provider === record.provider && model.modelId === record.modelId,
  )
  Object.assign(formData, {
    id: modelId,
    modelName: catalogModel?.modelName ?? record.modelName,
    provider: catalogModel?.provider ?? record.provider,
    modelId: catalogModel?.modelId ?? record.modelId,
    description: record.description,
    baseUrl: record.baseUrl ?? catalogModel?.defaultBaseUrl,
    apiKey: '',
    maxTokens: record.maxTokens ?? catalogModel?.defaultMaxTokens,
    temperature: record.temperature ?? catalogModel?.defaultTemperature,
    isEnabled: record.isEnabled === 1,
    modelType: record.modelType ?? catalogModel?.defaultModelType,
    supportsThinking: catalogModel?.supportsThinking ?? record.supportsThinking ?? 0,
    sortOrder: record.sortOrder,
    configJson: record.configJson,
    protocol:
      getProtocolFromConfig(record.configJson) ??
      catalogModel?.defaultProtocol ??
      'openai_chat_completions',
  })
  modalVisible.value = true
}

const handleModelSelect = (modelKey: string) => {
  const separatorIndex = modelKey.indexOf(':')
  if (separatorIndex <= 0 || separatorIndex === modelKey.length - 1) return

  const provider = modelKey.slice(0, separatorIndex)
  const modelId = modelKey.slice(separatorIndex + 1)
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
  formRef.value?.clearValidate()
  editingModelHasApiKey.value = false
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
    configJson: undefined,
    protocol: 'openai_chat_completions',
  })
  if (supportedModels.value.length > 0) {
    applyCatalogModel(supportedModels.value[0])
  }
}

const closeModal = () => {
  if (submitting.value || testingConfig.value) return
  modalVisible.value = false
  resetForm()
}

const buildModelPayload = (): API.AiModelAddRequest => {
  const apiKey = formData.apiKey?.trim()
  return {
    modelName: formData.modelName?.trim(),
    provider: formData.provider?.trim(),
    modelId: formData.modelId?.trim(),
    description: formData.description?.trim(),
    baseUrl: normalizeOpenAiBaseUrl(formData.baseUrl),
    maxTokens: formData.maxTokens,
    temperature: formData.temperature,
    isEnabled: formData.isEnabled ? 1 : 0,
    modelType: formData.modelType,
    supportsThinking: formData.supportsThinking,
    sortOrder: formData.sortOrder,
    configJson: formData.configJson,
    protocol: formData.protocol,
    ...(apiKey ? { apiKey } : {}),
  }
}

// 提交表单
const handleSubmit = async () => {
  if (submitting.value || testingConfig.value) return

  normalizeFormBaseUrl()
  try {
    await formRef.value?.validateFields()
  } catch {
    return
  }

  const payload = buildModelPayload()
  if (!isEditing.value && !payload.apiKey) {
    message.warning('新增模型时必须输入 API 密钥')
    return
  }

  submitting.value = true
  try {
    if (isEditing.value) {
      const modelId = normalizeModelId(formData.id)
      if (!modelId) {
        message.warning('模型 ID 无效')
        return
      }
      const res = await updateModel({ ...payload, id: modelId })
      if (res.data.code !== 0) {
        message.error(`更新失败：${res.data.message || '服务异常'}`)
        return
      }
      message.success('更新成功')
    } else {
      const res = await addModel(payload)
      if (res.data.code !== 0) {
        message.error(`添加失败：${res.data.message || '服务异常'}`)
        return
      }
      message.success('添加成功')
    }
    modalVisible.value = false
    resetForm()
    await refreshModelState()
  } catch (error) {
    console.error('Failed to save model', error)
    message.error(`${isEditing.value ? '更新' : '添加'}失败，请检查网络后重试`)
  } finally {
    submitting.value = false
  }
}

// 切换启用状态
const handleToggleEnabled = async (id: number | undefined, checked: boolean) => {
  const modelId = normalizeModelId(id)
  if (!modelId || isModelBusy(modelId)) return

  await withModelLock(togglingModelIds, modelId, async () => {
    try {
      const res = await toggleModelEnabled({ id: modelId })
      if (res.data.code !== 0) {
        message.error(`操作失败：${res.data.message || '服务异常'}`)
        return
      }
      message.success(checked ? '已启用' : '已禁用')
      await refreshModelState()
    } catch (error) {
      console.error('Failed to toggle model', error)
      message.error('操作失败，请检查网络后重试')
    }
  })
}

// 测试已保存的模型连接
const handleTestConnection = async (id: number | undefined) => {
  const modelId = normalizeModelId(id)
  if (!modelId || isModelBusy(modelId)) return

  await withModelLock(testingModelIds, modelId, async () => {
    try {
      const res = await testModelConnection({ id: modelId })
      if (res.data.code !== 0) {
        message.error(`连接测试失败：${res.data.message || '请检查配置'}`)
        return
      }
      message.success('连接测试成功')
    } catch (error) {
      console.error('Failed to test saved model connection', error)
      message.error('连接测试失败，请检查网络或模型配置')
    }
  })
}

const handleTestCurrentConfig = async () => {
  if (testingConfig.value || submitting.value) return

  normalizeFormBaseUrl()
  try {
    await formRef.value?.validateFields()
  } catch {
    return
  }

  const payload = buildModelPayload()
  if (!payload.apiKey) {
    message.warning(
      isEditing.value && editingModelHasApiKey.value
        ? '测试当前表单需重新输入 API 密钥；测试已保存配置请使用列表中的“测试”按钮'
        : '请输入 API 密钥',
    )
    return
  }

  testingConfig.value = true
  try {
    const res = await testModelConnectionByConfig(payload)
    if (res.data.code !== 0) {
      message.error(`连接测试失败：${res.data.message || '请检查配置'}`)
      return
    }
    message.success(res.data.data?.message ?? '连接测试成功')
  } catch (error) {
    console.error('Failed to test current model config', error)
    message.error('连接测试失败，请检查网络或当前配置')
  } finally {
    testingConfig.value = false
  }
}

// 删除模型
const handleDelete = async (id: number | undefined) => {
  const modelId = normalizeModelId(id)
  if (!modelId || isModelBusy(modelId)) return

  await withModelLock(deletingModelIds, modelId, async () => {
    try {
      const res = await deleteModel({ id: modelId })
      if (res.data.code !== 0 || res.data.data !== true) {
        message.error(`删除失败：${res.data.message || '服务异常'}`)
        return
      }
      if (data.value.length === 1 && (searchParams.pageNum ?? 1) > 1) {
        searchParams.pageNum = (searchParams.pageNum ?? 1) - 1
      }
      message.success('删除成功')
      await refreshModelState()
    } catch (error) {
      console.error('Failed to delete model', error)
      message.error('删除失败，请检查网络后重试')
    }
  })
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
    chat: '快速',
    reasoning: '思考',
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
  void Promise.all([fetchCatalog(), fetchData(), fetchEnabledModels()])
})
</script>

<style scoped>
.load-error {
  margin-bottom: 14px;
}

.model-info-cell {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}

.model-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  flex: 0 0 auto;
  border: 2px solid rgba(255, 255, 255, 0.85);
  border-radius: 12px;
  color: #fff;
  font-size: 14px;
  font-weight: 760;
  box-shadow: 0 8px 20px rgba(63, 88, 120, 0.13);
}

.provider-deepseek {
  background: linear-gradient(135deg, #4f67df, #7c64d7);
}

.provider-openai {
  background: linear-gradient(135deg, #168c78, #42b39f);
}

.provider-custom {
  background: linear-gradient(135deg, #ca831a, #edb13c);
}

.provider-xiaomi {
  background: linear-gradient(135deg, #df664f, #f18e49);
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
  overflow: hidden;
  color: var(--color-ink-strong);
  font-weight: 680;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.model-id,
.tokens-value {
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
}

.model-id {
  margin-top: 2px;
  color: #91a0b2;
  font-size: 11px;
}

.tokens-value {
  color: #526579;
}

.url-text {
  display: inline-block;
  max-width: 180px;
  overflow: hidden;
  color: var(--color-ink-soft);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.soft-tag {
  border: 0;
  border-radius: 999px;
}

.action-button {
  border-radius: 10px;
}

.secondary-action-button {
  border-color: rgba(47, 139, 255, 0.2);
  color: #267ff1;
  background: rgba(47, 139, 255, 0.07);
}

.secondary-action-button:hover,
.secondary-action-button:focus {
  border-color: rgba(47, 139, 255, 0.34);
  color: #176bd1;
  background: rgba(47, 139, 255, 0.11);
}

.model-form {
  margin-top: 16px;
}

:deep(.ant-modal-content) {
  overflow: hidden;
  border: 1px solid rgba(119, 150, 187, 0.16);
  border-radius: 21px;
  box-shadow: 0 28px 80px rgba(39, 59, 85, 0.2);
}

.model-form :deep(.ant-input),
.model-form :deep(.ant-select-selector),
.model-form :deep(.ant-btn),
.model-form :deep(.ant-input-number) {
  border-radius: 10px !important;
}

.model-form :deep(.ant-form-item) {
  margin-bottom: 16px;
}

@media (max-width: 760px) {
  .model-name-row {
    align-items: flex-start;
    flex-direction: column;
    gap: 4px;
  }
}
</style>
