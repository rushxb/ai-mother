<template>
  <AppModalShell
    v-model:open="visible"
    eyebrow="Application profile"
    :title="appName"
    description="查看应用归属、生成模式与当前生命周期信息。"
    :width="600"
    tone="neutral"
  >
    <template #icon>
      <AppstoreOutlined />
    </template>

    <div class="app-detail-content">
      <section class="app-summary" aria-label="应用概览">
        <div class="app-summary__identity">
          <span class="summary-label">创建者</span>
          <UserInfo :user="app?.user" size="small" />
        </div>
        <div class="app-summary__status">
          <span class="status-dot" :class="{ active: app?.isGenerating }" />
          {{ generationStatus }}
        </div>
      </section>

      <dl class="app-metadata">
        <div v-for="item in metadataItems" :key="item.label" class="metadata-item">
          <dt>
            <component :is="item.icon" />
            {{ item.label }}
          </dt>
          <dd :class="{ 'metadata-item__value--mono': item.monospace }">{{ item.value }}</dd>
        </div>
      </dl>

      <section v-if="app?.initPrompt" class="prompt-preview">
        <span class="summary-label">初始需求</span>
        <p>{{ app.initPrompt }}</p>
      </section>
    </div>

    <template v-if="showActions" #footer>
      <a-popconfirm
        title="确定删除这个应用吗？"
        description="删除后无法恢复，请确认已经备份需要保留的内容。"
        ok-text="确认删除"
        cancel-text="取消"
        :ok-button-props="{ danger: true }"
        @confirm="handleDelete"
      >
        <a-button class="danger-button" danger>
          <template #icon>
            <DeleteOutlined />
          </template>
          删除应用
        </a-button>
      </a-popconfirm>
      <a-button type="primary" class="primary-action" @click="handleEdit">
        <template #icon>
          <EditOutlined />
        </template>
        编辑应用
      </a-button>
    </template>
  </AppModalShell>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import {
  AppstoreOutlined,
  CalendarOutlined,
  CodeOutlined,
  DeleteOutlined,
  EditOutlined,
  FieldTimeOutlined,
  NumberOutlined,
} from '@ant-design/icons-vue'
import AppModalShell from '@/components/common/AppModalShell.vue'
import UserInfo from '@/components/UserInfo.vue'
import { formatCodeGenType } from '@/utils/codeGenTypes'
import { formatTime } from '@/utils/time'

interface Props {
  open: boolean
  app?: API.AppVO
  showActions?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  showActions: false,
})

const emit = defineEmits<{
  'update:open': [value: boolean]
  edit: []
  delete: []
}>()

const visible = computed({
  get: () => props.open,
  set: (value: boolean) => emit('update:open', value),
})

const appName = computed(() => props.app?.appName?.trim() || '未命名应用')

const generationStatus = computed(() => {
  if (!props.app) {
    return '等待应用数据'
  }
  return props.app.isGenerating ? props.app.generatingMessage || '正在生成' : '当前可用'
})

const metadataItems = computed(() => [
  {
    label: '应用编号',
    value: props.app?.id ? String(props.app.id) : '暂无',
    icon: NumberOutlined,
    monospace: true,
  },
  {
    label: '生成模式',
    value: formatCodeGenType(props.app?.codeGenType),
    icon: CodeOutlined,
    monospace: false,
  },
  {
    label: '创建时间',
    value: formatTime(props.app?.createTime) || '暂无记录',
    icon: CalendarOutlined,
    monospace: false,
  },
  {
    label: '最近更新',
    value: formatTime(props.app?.updateTime) || '暂无记录',
    icon: FieldTimeOutlined,
    monospace: false,
  },
])

const handleEdit = () => emit('edit')
const handleDelete = () => emit('delete')
</script>

<style scoped>
.app-detail-content {
  display: grid;
  gap: 18px;
}

.app-summary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 16px;
  border: 1px solid var(--color-line, rgba(112, 140, 175, 0.18));
  border-radius: var(--radius-md, 16px);
  background:
    linear-gradient(135deg, rgba(47, 139, 255, 0.06), rgba(60, 201, 187, 0.035)),
    rgba(255, 255, 255, 0.72);
}

.app-summary__identity {
  display: grid;
  gap: 8px;
  min-width: 0;
}

.summary-label {
  color: var(--color-ink-soft, #6f8198);
  font-size: 11px;
  font-weight: 750;
  letter-spacing: 0.08em;
  line-height: 1.4;
  text-transform: uppercase;
}

.app-summary__status {
  display: inline-flex;
  align-items: center;
  flex: none;
  gap: 7px;
  max-width: 48%;
  padding: 7px 10px;
  border: 1px solid rgba(60, 201, 187, 0.18);
  border-radius: 999px;
  background: rgba(60, 201, 187, 0.08);
  color: #147c72;
  font-size: 12px;
  font-weight: 700;
}

.status-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: currentColor;
}

.status-dot.active {
  animation: statusPulse 1.6s var(--ease-out, ease) infinite;
}

.app-metadata {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
  margin: 0;
}

.metadata-item {
  min-width: 0;
  padding: 14px;
  border: 1px solid var(--color-line, rgba(112, 140, 175, 0.18));
  border-radius: 14px;
  background: rgba(246, 249, 253, 0.66);
}

.metadata-item dt {
  display: flex;
  align-items: center;
  gap: 7px;
  margin-bottom: 7px;
  color: var(--color-ink-soft, #6f8198);
  font-size: 12px;
  font-weight: 650;
}

.metadata-item dd {
  overflow: hidden;
  margin: 0;
  color: var(--color-ink-strong, #102033);
  font-size: 13px;
  font-weight: 700;
  line-height: 1.5;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.metadata-item__value--mono {
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', monospace;
}

.prompt-preview {
  display: grid;
  gap: 8px;
  padding: 15px 16px;
  border-left: 3px solid rgba(47, 139, 255, 0.46);
  border-radius: 4px 14px 14px 4px;
  background: rgba(47, 139, 255, 0.055);
}

.prompt-preview p {
  display: -webkit-box;
  overflow: hidden;
  margin: 0;
  color: var(--color-ink, #2f4158);
  font-size: 13px;
  line-height: 1.7;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 3;
}

:deep(.primary-action.ant-btn) {
  min-width: 112px;
  border-color: var(--color-primary, #2f8bff);
  border-radius: 11px;
  background: var(--color-primary, #2f8bff);
  box-shadow: 0 10px 22px rgba(47, 139, 255, 0.2);
}

:deep(.danger-button.ant-btn) {
  margin-right: auto;
  border-radius: 11px;
}

@keyframes statusPulse {
  50% {
    box-shadow: 0 0 0 5px rgba(60, 201, 187, 0.14);
  }
}

@media (max-width: 560px) {
  .app-summary {
    align-items: flex-start;
    flex-direction: column;
  }

  .app-summary__status {
    max-width: 100%;
  }

  .app-metadata {
    grid-template-columns: 1fr;
  }
}

@media (prefers-reduced-motion: reduce) {
  .status-dot.active {
    animation: none;
  }
}
</style>
