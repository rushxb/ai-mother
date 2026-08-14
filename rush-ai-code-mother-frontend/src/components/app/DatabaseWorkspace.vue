<template>
  <div class="database-workspace">
    <section v-if="!databaseEnabled" class="database-guide">
      <div class="database-guide-hero">
        <div class="database-kicker-row">
          <span class="database-kicker">Rush Database</span>
          <span class="database-service-state">
            <span />
            Managed service
          </span>
        </div>
        <h2>为应用接入托管数据能力</h2>
        <p>一次开通即可补齐结构化数据、文件存储、用户体系与外部服务连接，为后续功能迭代保留稳定扩展点。</p>
      </div>

      <div class="database-capability-flow">
        <article
          v-for="(capability, index) in capabilities"
          :key="capability.title"
          class="database-capability-card"
        >
          <div class="capability-card-header">
            <div class="capability-icon">
              <component :is="capability.icon" />
            </div>
            <span class="capability-index">0{{ index + 1 }}</span>
          </div>
          <strong>{{ capability.title }}</strong>
          <span>{{ capability.description }}</span>
          <div v-if="index < capabilities.length - 1" class="capability-connector" />
        </article>
      </div>

      <div class="database-provisioning">
        <div>
          <ClockCircleOutlined />
          <span>
            <strong>秒级开通</strong>
            自动创建隔离资源并连接当前应用
          </span>
        </div>
        <div>
          <SafetyCertificateOutlined />
          <span>
            <strong>默认安全</strong>
            资源权限仅授予当前应用所有者
          </span>
        </div>
      </div>

      <div class="database-actions">
        <a-button
          class="database-primary-button"
          type="primary"
          size="large"
          :loading="enabling"
          :disabled="!isOwner || isGenerating"
          @click="enableDatabase"
        >
          <template #icon>
            <DatabaseOutlined />
          </template>
          新建 Database 资源
        </a-button>
        <a-dropdown>
          <a-button class="database-secondary-button" size="large" :disabled="!isOwner || isGenerating">
            关联既有
            <DownOutlined />
          </a-button>
          <template #overlay>
            <a-menu>
              <a-menu-item key="coming-soon" disabled>后续开放既有资源关联</a-menu-item>
            </a-menu>
          </template>
        </a-dropdown>
        <span class="database-action-hint">{{ enableActionHint }}</span>
      </div>

      <div class="database-recycle-note">
        <AlertOutlined />
        <span>
          因资源有限，平台会定期回收 7 天内未使用的资源。需要长期保留时，请保持项目活跃或提前导出关键数据。
        </span>
      </div>
    </section>

    <section v-else class="database-settings">
      <aside class="database-settings-sidebar">
        <div class="settings-sidebar-heading">
          <DatabaseOutlined />
          <div>
            <strong>Database</strong>
            <span>项目资源控制台</span>
          </div>
        </div>
        <button
          v-for="item in settingNavItems"
          :key="item"
          type="button"
          class="settings-nav-item"
          :class="{ active: item === 'Database 设置' }"
          :disabled="item !== 'Database 设置'"
        >
          {{ item }}
          <span v-if="item !== 'Database 设置'">Soon</span>
        </button>
      </aside>

      <main class="database-settings-main">
        <article class="connected-project-card">
          <div class="connected-project-copy">
            <span class="settings-section-label">已连接项目</span>
            <div class="connected-project-name-row">
              <h2>{{ resourceName }}</h2>
              <EditOutlined />
            </div>
            <a
              v-if="safeDatabaseUrl"
              :href="safeDatabaseUrl"
              target="_blank"
              rel="noopener noreferrer"
              class="database-url"
            >
              <LinkOutlined />
              {{ databaseUrl }}
              <ExportOutlined />
            </a>
            <span v-else class="database-url database-url--unavailable">
              <LinkOutlined />
              {{ databaseUrl }}
            </span>
          </div>
          <div class="connected-project-status">
            <CheckCircleOutlined />
            Active
          </div>
        </article>

        <article class="sql-policy-card">
          <div class="sql-policy-header">
            <span class="settings-section-label">SQL 执行策略配置</span>
            <strong>选择 AI 执行 SQL 时的确认方式</strong>
          </div>
          <div class="sql-policy-options">
            <button
              v-for="option in sqlPolicyOptions"
              :key="option.value"
              type="button"
              class="sql-policy-option"
              :class="{ active: activeSqlPolicy === option.value }"
              disabled
            >
              <span class="policy-radio" aria-hidden="true" />
              <strong>{{ option.label }}</strong>
              <span>{{ option.description }}</span>
            </button>
          </div>
          <p class="sql-policy-note">策略修改能力将在后续版本开放，当前配置由资源安全策略统一托管。</p>
        </article>
      </main>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { message } from 'ant-design-vue'
import {
  AlertOutlined,
  AppstoreOutlined,
  CheckCircleOutlined,
  ClockCircleOutlined,
  DatabaseOutlined,
  DownOutlined,
  EditOutlined,
  ExportOutlined,
  FileTextOutlined,
  LinkOutlined,
  SafetyCertificateOutlined,
  TeamOutlined,
} from '@ant-design/icons-vue'
import { enableAppDatabase } from '@/api/appController'
import { resolveSafeBrowserUrl } from '@/utils/browser'

const props = defineProps<{
  app?: API.OwnerAppVO
  isOwner: boolean
  isGenerating: boolean
}>()

const emit = defineEmits<{
  enabled: [resource: API.AppDatabaseResourceVO]
}>()

const enabling = ref(false)

const databaseEnabled = computed(() => Boolean(props.app?.databaseResource?.enabled))
const resourceName = computed(
  () => props.app?.databaseResource?.resourceName || `${props.app?.appName || '未命名应用'} Database`,
)
const databaseUrl = computed(
  () => props.app?.databaseResource?.databaseUrl || 'https://[id].database.nocode.cn',
)
const safeDatabaseUrl = computed(() => resolveSafeBrowserUrl(props.app?.databaseResource?.databaseUrl || ''))
const activeSqlPolicy = computed(
  () => props.app?.databaseResource?.sqlExecutionPolicy || 'ask_every_time',
)

const enableActionHint = computed(() => {
  if (!props.isOwner) {
    return '仅应用创建者可管理 Database 资源'
  }
  if (props.isGenerating) {
    return '代码生成结束后即可创建资源'
  }
  return '资源将自动绑定当前应用'
})

const capabilities = [
  {
    title: '储存数据与状态',
    description: '持久化业务数据、页面状态与应用配置。',
    icon: DatabaseOutlined,
  },
  {
    title: '储存多媒体文件',
    description: '托管图片、附件与用户上传资源。',
    icon: FileTextOutlined,
  },
  {
    title: '关联账号，管理用户',
    description: '为登录、权限和用户资料预留统一入口。',
    icon: TeamOutlined,
  },
  {
    title: '连接外部功能与数据',
    description: '通过 API 串联第三方服务和外部数据。',
    icon: AppstoreOutlined,
  },
]

const settingNavItems = ['Database 设置', '数据表', '数据安全策略', '存储桶', '登录与认证', '用户管理']

const sqlPolicyOptions = [
  {
    value: 'ask_every_time',
    label: '每次询问',
    description: '手动确认后执行',
  },
  {
    value: 'always_allow',
    label: '始终允许',
    description: '自动执行所有 SQL',
  },
]

const enableDatabase = async () => {
  if (!props.app?.id || enabling.value || props.isGenerating || !props.isOwner) {
    return
  }

  enabling.value = true
  try {
    const response = await enableAppDatabase({ appId: props.app.id })
    if (response.data.code === 0 && response.data.data) {
      emit('enabled', response.data.data)
      message.success('Database 资源已启用')
      return
    }
    message.error(response.data.message || '启用 Database 失败')
  } catch (error) {
    console.error('启用 Database 失败：', error)
    message.error('启用 Database 失败，请重试')
  } finally {
    enabling.value = false
  }
}
</script>

<style scoped>
.database-workspace {
  --database-ink-strong: var(--chat-ink-strong, var(--color-ink-strong, #102033));
  --database-ink: var(--chat-ink, var(--color-ink, #2f4158));
  --database-muted: var(--chat-ink-soft, var(--color-ink-soft, #6f8198));
  --database-primary: var(--chat-primary, var(--color-primary, #2f8bff));
  --database-secondary: var(--chat-secondary, var(--color-secondary, #3cc9bb));
  --database-line: var(--chat-line, var(--color-line, rgba(112, 140, 175, 0.18)));
  flex: 1;
  min-height: 0;
  height: 100%;
  overflow: auto;
  background:
    radial-gradient(circle at 14% 6%, rgba(47, 139, 255, 0.07), transparent 32%),
    linear-gradient(rgba(112, 140, 175, 0.045) 1px, transparent 1px),
    linear-gradient(90deg, rgba(112, 140, 175, 0.045) 1px, transparent 1px),
    rgba(246, 249, 253, 0.72);
  background-size: auto, 28px 28px, 28px 28px, auto;
  color: var(--database-ink-strong);
  scrollbar-color: rgba(112, 140, 175, 0.34) transparent;
  scrollbar-width: thin;
}

.database-guide {
  min-height: 100%;
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: clamp(22px, 3vw, 30px);
  width: min(1180px, 100%);
  margin: 0 auto;
  padding: clamp(28px, 4.5vw, 56px);
}

.database-guide-hero {
  max-width: 760px;
}

.database-kicker-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}

.database-kicker,
.settings-section-label {
  color: var(--database-primary);
  font-size: 11px;
  font-weight: 800;
  letter-spacing: 0.12em;
  text-transform: uppercase;
}

.database-service-state {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--database-muted);
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.database-service-state > span {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--database-secondary);
  box-shadow: 0 0 0 4px rgba(60, 201, 187, 0.12);
}

.database-guide-hero h2 {
  margin: 0;
  color: var(--database-ink-strong);
  font-size: clamp(28px, 4vw, 40px);
  font-weight: 780;
  letter-spacing: -0.045em;
  line-height: 1.12;
}

.database-guide-hero p {
  max-width: 680px;
  margin: 14px 0 0;
  color: var(--database-ink);
  font-size: 14px;
  line-height: 1.8;
}

.database-capability-flow {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 16px;
}

.database-capability-card {
  position: relative;
  min-width: 0;
  display: grid;
  align-content: start;
  gap: 10px;
  padding: 20px;
  border: 1px solid var(--database-line);
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.76);
  box-shadow:
    0 16px 36px rgba(68, 96, 136, 0.07),
    inset 0 1px 0 rgba(255, 255, 255, 0.82);
  backdrop-filter: blur(14px);
  transition:
    transform 0.25s var(--chat-ease, var(--ease-out, ease)),
    border-color 0.25s var(--chat-ease, var(--ease-out, ease)),
    box-shadow 0.25s var(--chat-ease, var(--ease-out, ease));
}

.database-capability-card:hover {
  transform: translateY(-3px);
  border-color: rgba(47, 139, 255, 0.24);
  box-shadow: 0 22px 42px rgba(68, 96, 136, 0.12);
}

.capability-card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.capability-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 42px;
  height: 42px;
  border: 1px solid rgba(47, 139, 255, 0.12);
  border-radius: 13px;
  background: rgba(47, 139, 255, 0.08);
  color: var(--database-primary);
  font-size: 19px;
}

.capability-index {
  color: rgba(111, 129, 152, 0.58);
  font-size: 11px;
  font-weight: 800;
  letter-spacing: 0.1em;
}

.database-capability-card > strong {
  color: var(--database-ink-strong);
  font-size: 14px;
  line-height: 1.4;
}

.database-capability-card > span:not(.capability-index) {
  color: var(--database-muted);
  font-size: 12px;
  line-height: 1.65;
}

.capability-connector {
  position: absolute;
  top: 42px;
  right: -17px;
  z-index: 2;
  width: 17px;
  border-top: 1px dashed rgba(47, 139, 255, 0.3);
}

.database-provisioning {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.database-provisioning > div {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 13px 15px;
  border: 1px solid var(--database-line);
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.58);
  color: var(--database-primary);
}

.database-provisioning > div > span {
  display: grid;
  gap: 2px;
  color: var(--database-muted);
  font-size: 11px;
}

.database-provisioning strong {
  color: var(--database-ink-strong);
  font-size: 12px;
}

.database-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
}

:deep(.database-primary-button.ant-btn),
:deep(.database-secondary-button.ant-btn) {
  height: 44px;
  border-radius: 12px;
  font-weight: 720;
}

:deep(.database-primary-button.ant-btn) {
  min-width: 218px;
  border-color: var(--database-primary);
  background: var(--database-primary);
  box-shadow: 0 14px 28px rgba(47, 139, 255, 0.2);
}

:deep(.database-primary-button.ant-btn:not(:disabled):hover) {
  border-color: var(--chat-primary-strong, var(--color-primary-strong, #176fdd));
  background: var(--chat-primary-strong, var(--color-primary-strong, #176fdd));
}

.database-action-hint {
  color: var(--database-muted);
  font-size: 11px;
}

.database-recycle-note {
  display: flex;
  align-items: flex-start;
  gap: 9px;
  padding: 12px 14px;
  border: 1px solid rgba(214, 151, 52, 0.16);
  border-radius: 12px;
  background: rgba(255, 250, 236, 0.64);
  color: #8a6428;
  font-size: 11px;
  line-height: 1.65;
}

.database-recycle-note :deep(.anticon) {
  margin-top: 3px;
}

.database-settings {
  min-height: 100%;
  display: grid;
  grid-template-columns: 224px minmax(0, 1fr);
}

.database-settings-sidebar {
  display: flex;
  flex-direction: column;
  gap: 5px;
  padding: 20px 14px;
  border-right: 1px solid var(--database-line);
  background: rgba(255, 255, 255, 0.7);
  backdrop-filter: blur(18px);
}

.settings-sidebar-heading {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 0 5px 16px;
  padding-bottom: 16px;
  border-bottom: 1px solid var(--database-line);
  color: var(--database-primary);
  font-size: 18px;
}

.settings-sidebar-heading > div {
  display: grid;
  gap: 2px;
}

.settings-sidebar-heading strong {
  color: var(--database-ink-strong);
  font-size: 13px;
}

.settings-sidebar-heading span {
  color: var(--database-muted);
  font-size: 10px;
}

.settings-nav-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  min-height: 39px;
  padding: 0 11px;
  border: 0;
  border-radius: 10px;
  background: transparent;
  color: var(--database-muted);
  font-size: 12px;
  font-weight: 650;
  text-align: left;
}

.settings-nav-item.active {
  background: rgba(47, 139, 255, 0.09);
  color: var(--database-primary);
  box-shadow: inset 0 0 0 1px rgba(47, 139, 255, 0.1);
}

.settings-nav-item > span {
  font-size: 8px;
  letter-spacing: 0.08em;
  opacity: 0.62;
  text-transform: uppercase;
}

.database-settings-main {
  min-width: 0;
  display: grid;
  align-content: start;
  gap: 16px;
  padding: clamp(20px, 3vw, 34px);
}

.connected-project-card,
.sql-policy-card {
  border: 1px solid var(--database-line);
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.78);
  box-shadow: 0 16px 38px rgba(68, 96, 136, 0.08);
  backdrop-filter: blur(16px);
}

.connected-project-card {
  display: flex;
  justify-content: space-between;
  gap: 18px;
  padding: 22px;
}

.connected-project-copy {
  min-width: 0;
}

.connected-project-name-row {
  display: flex;
  align-items: center;
  gap: 9px;
  margin-top: 8px;
}

.connected-project-name-row h2 {
  overflow: hidden;
  margin: 0;
  color: var(--database-ink-strong);
  font-size: 20px;
  font-weight: 760;
  letter-spacing: -0.02em;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.connected-project-name-row :deep(.anticon) {
  color: var(--database-muted);
  font-size: 13px;
}

.database-url {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  max-width: 100%;
  margin-top: 11px;
  color: var(--database-primary);
  font-size: 12px;
  word-break: break-all;
}

.database-url--unavailable {
  color: var(--database-muted);
  cursor: not-allowed;
}

.connected-project-status {
  align-self: flex-start;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 9px;
  border-radius: 999px;
  background: rgba(60, 201, 187, 0.1);
  color: #147c72;
  font-size: 10px;
  font-weight: 800;
  letter-spacing: 0.06em;
  text-transform: uppercase;
}

.sql-policy-card {
  display: grid;
  gap: 16px;
  padding: 22px;
}

.sql-policy-header {
  display: grid;
  gap: 7px;
}

.sql-policy-header strong {
  color: var(--database-ink-strong);
  font-size: 16px;
}

.sql-policy-options {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.sql-policy-option {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  gap: 5px 10px;
  padding: 15px;
  border: 1px solid var(--database-line);
  border-radius: 13px;
  background: rgba(246, 249, 253, 0.7);
  text-align: left;
}

.sql-policy-option.active {
  border-color: rgba(47, 139, 255, 0.3);
  background: rgba(47, 139, 255, 0.06);
  box-shadow: inset 0 0 0 1px rgba(47, 139, 255, 0.08);
}

.policy-radio {
  grid-row: 1 / 3;
  width: 14px;
  height: 14px;
  margin-top: 2px;
  border: 1px solid rgba(112, 140, 175, 0.38);
  border-radius: 50%;
}

.sql-policy-option.active .policy-radio {
  border: 4px solid var(--database-primary);
}

.sql-policy-option strong {
  color: var(--database-ink-strong);
  font-size: 13px;
}

.sql-policy-option > span:last-child {
  color: var(--database-muted);
  font-size: 11px;
}

.sql-policy-note {
  margin: 0;
  color: var(--database-muted);
  font-size: 11px;
  line-height: 1.6;
}

@media (max-width: 1100px) {
  .database-capability-flow {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .capability-connector {
    display: none;
  }
}

@media (max-width: 720px) {
  .database-guide {
    justify-content: flex-start;
    padding: 24px 18px;
  }

  .database-capability-flow,
  .database-provisioning,
  .sql-policy-options,
  .database-settings {
    grid-template-columns: 1fr;
  }

  .database-settings-sidebar {
    border-right: 0;
    border-bottom: 1px solid var(--database-line);
  }

  .connected-project-card {
    flex-direction: column;
  }
}

@media (prefers-reduced-motion: reduce) {
  .database-capability-card:hover {
    transform: none;
  }
}
</style>
