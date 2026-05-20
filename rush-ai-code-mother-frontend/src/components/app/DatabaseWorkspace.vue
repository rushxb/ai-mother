<template>
  <div class="database-workspace">
    <section v-if="!databaseEnabled" class="database-guide">
      <div class="database-guide-hero">
        <span class="database-kicker">Rush Database</span>
        <h2>启用 Rush Database 服务</h2>
        <p>一站式托管后端数据服务，开箱即用地为 AI 生成项目补齐数据、文件、用户与外部连接能力。</p>
      </div>

      <div class="database-capability-flow">
        <article
          v-for="(capability, index) in capabilities"
          :key="capability.title"
          class="database-capability-card"
        >
          <div class="capability-icon">
            <component :is="capability.icon" />
          </div>
          <strong>{{ capability.title }}</strong>
          <span>{{ capability.description }}</span>
          <div v-if="index < capabilities.length - 1" class="capability-connector"></div>
        </article>
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
      </div>

      <div class="database-recycle-note">
        因资源有限，平台会定期回收 7 天内未使用的资源，请在需要保留数据时保持项目活跃或提前导出关键数据。
      </div>
    </section>

    <section v-else class="database-settings">
      <aside class="database-settings-sidebar">
        <button
          v-for="item in settingNavItems"
          :key="item"
          type="button"
          class="settings-nav-item"
          :class="{ active: item === 'Database 设置' }"
        >
          {{ item }}
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
            <a :href="databaseUrl" target="_blank" rel="noreferrer" class="database-url">
              {{ databaseUrl }}
              <ExportOutlined />
            </a>
          </div>
          <div class="connected-project-status">Active</div>
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
            >
              <strong>{{ option.label }}</strong>
              <span>{{ option.description }}</span>
            </button>
          </div>
        </article>
      </main>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { message } from 'ant-design-vue'
import {
  AppstoreOutlined,
  DatabaseOutlined,
  DownOutlined,
  EditOutlined,
  ExportOutlined,
  FileTextOutlined,
  TeamOutlined,
} from '@ant-design/icons-vue'
import { enableAppDatabase } from '@/api/appController'

const props = defineProps<{
  app?: API.AppVO
  isOwner: boolean
  isGenerating: boolean
}>()

const emit = defineEmits<{
  enabled: [resource: API.AppDatabaseResourceVO]
}>()

const enabling = ref(false)

const databaseEnabled = computed(() => Boolean(props.app?.databaseResource?.enabled))

const resourceName = computed(() => {
  return props.app?.databaseResource?.resourceName || `${props.app?.appName || '未命名应用'} Database`
})

const databaseUrl = computed(() => {
  return props.app?.databaseResource?.databaseUrl || 'https://[id].database.nocode.cn'
})

const activeSqlPolicy = computed(() => {
  return props.app?.databaseResource?.sqlExecutionPolicy || 'ask_every_time'
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
  if (!props.app?.id || enabling.value || props.isGenerating) {
    return
  }
  enabling.value = true
  try {
    const res = await enableAppDatabase({
      appId: props.app.id,
    })
    if (res.data.code === 0 && res.data.data) {
      emit('enabled', res.data.data)
      message.success('Database 资源已启用')
      return
    }
    message.error(res.data.message || '启用 Database 失败')
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
  flex: 1;
  min-height: 0;
  height: 100%;
  overflow: auto;
  background: #f9fafb;
  color: #111827;
}

.database-guide {
  min-height: 100%;
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 30px;
  padding: 44px;
}

.database-guide-hero {
  max-width: 720px;
}

.database-kicker,
.settings-section-label {
  display: block;
  margin-bottom: 8px;
  color: #6b7280;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0;
  text-transform: uppercase;
}

.database-guide-hero h2 {
  margin: 0;
  color: #111827;
  font-size: 30px;
  line-height: 1.2;
  font-weight: 800;
  letter-spacing: 0;
}

.database-guide-hero p {
  margin: 12px 0 0;
  max-width: 620px;
  color: #4b5563;
  font-size: 15px;
  line-height: 1.8;
}

.database-capability-flow {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 22px;
}

.database-capability-card {
  position: relative;
  min-width: 0;
  display: grid;
  gap: 10px;
  padding: 22px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #ffffff;
  box-shadow: 0 14px 32px rgba(17, 24, 39, 0.07);
}

.capability-icon {
  width: 44px;
  height: 44px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 8px;
  background: #f3f4f6;
  color: #111827;
  font-size: 22px;
}

.database-capability-card strong {
  color: #111827;
  font-size: 15px;
  line-height: 1.4;
}

.database-capability-card span {
  color: #6b7280;
  font-size: 13px;
  line-height: 1.7;
}

.capability-connector {
  position: absolute;
  top: 50%;
  right: -22px;
  width: 22px;
  border-top: 1px dashed #cbd5e1;
}

.database-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
}

.database-primary-button {
  min-width: 220px;
  height: 48px;
  border-color: #111827;
  background: #111827;
  border-radius: 8px;
  font-weight: 700;
  box-shadow: 0 16px 30px rgba(17, 24, 39, 0.18);
}

.database-primary-button:hover {
  border-color: #1f2937 !important;
  background: #1f2937 !important;
}

.database-secondary-button {
  height: 48px;
  border-radius: 8px;
  font-weight: 700;
}

.database-recycle-note {
  padding: 14px 16px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #ffffff;
  color: #6b7280;
  font-size: 13px;
  line-height: 1.7;
}

.database-settings {
  min-height: 100%;
  display: grid;
  grid-template-columns: 220px minmax(0, 1fr);
}

.database-settings-sidebar {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 20px 14px;
  border-right: 1px solid #e5e7eb;
  background: #ffffff;
}

.settings-nav-item {
  width: 100%;
  min-height: 40px;
  padding: 0 12px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: #4b5563;
  font-size: 13px;
  font-weight: 700;
  text-align: left;
  cursor: default;
}

.settings-nav-item.active {
  background: #111827;
  color: #ffffff;
}

.database-settings-main {
  min-width: 0;
  display: grid;
  align-content: start;
  gap: 18px;
  padding: 28px;
}

.connected-project-card,
.sql-policy-card {
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #ffffff;
  box-shadow: 0 12px 28px rgba(17, 24, 39, 0.06);
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
  gap: 10px;
}

.connected-project-name-row h2 {
  margin: 0;
  color: #111827;
  font-size: 20px;
  line-height: 1.3;
  font-weight: 800;
}

.database-url {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  max-width: 100%;
  margin-top: 10px;
  color: #374151;
  font-size: 13px;
  word-break: break-all;
}

.connected-project-status {
  align-self: flex-start;
  padding: 5px 10px;
  border-radius: 999px;
  background: #ecfdf5;
  color: #047857;
  font-size: 12px;
  font-weight: 800;
}

.sql-policy-card {
  display: grid;
  gap: 18px;
  padding: 22px;
}

.sql-policy-header strong {
  display: block;
  color: #111827;
  font-size: 17px;
}

.sql-policy-options {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.sql-policy-option {
  display: grid;
  gap: 6px;
  padding: 16px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #ffffff;
  text-align: left;
}

.sql-policy-option.active {
  border-color: #111827;
  box-shadow: inset 0 0 0 1px #111827;
}

.sql-policy-option strong {
  color: #111827;
  font-size: 14px;
}

.sql-policy-option span {
  color: #6b7280;
  font-size: 13px;
}

@media (max-width: 1100px) {
  .database-capability-flow {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .capability-connector {
    display: none;
  }
}

@media (max-width: 768px) {
  .database-guide {
    padding: 24px;
  }

  .database-capability-flow,
  .sql-policy-options,
  .database-settings {
    grid-template-columns: 1fr;
  }

  .database-settings-sidebar {
    border-right: 0;
    border-bottom: 1px solid #e5e7eb;
  }

  .connected-project-card {
    flex-direction: column;
  }
}
</style>
