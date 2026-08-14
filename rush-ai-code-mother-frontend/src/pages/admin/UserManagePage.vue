<template>
  <AdminPageFrame
    id="userManagePage"
    eyebrow="IDENTITY OPERATIONS"
    title="用户管理"
    description="统一查看账号身份、角色和积分状态，在不打断业务使用的前提下完成日常运营管理。"
  >
    <template #actions>
      <AdminSummaryBadge label="当前用户" :value="total" tone="blue" />
    </template>

    <AdminFilterPanel description="按账号或用户名定位目标用户，支持清空条件后快速恢复完整数据集。">
      <a-form class="toolbar-form" layout="inline" :model="searchParams" @finish="doSearch">
        <a-form-item label="账号">
          <a-input v-model:value="searchParams.userAccount" allow-clear placeholder="输入账号" />
        </a-form-item>
        <a-form-item label="用户名">
          <a-input v-model:value="searchParams.userName" allow-clear placeholder="输入用户名" />
        </a-form-item>
        <a-form-item>
          <a-space>
            <a-button type="primary" html-type="submit">搜索</a-button>
            <a-button @click="resetSearch">重置</a-button>
          </a-space>
        </a-form-item>
      </a-form>
    </AdminFilterPanel>

    <AdminDataPanel title="用户目录" description="账号身份、角色、积分与创建时间的实时管理视图。">
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
        :row-key="getUserRowKey"
        @change="doTableChange"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.dataIndex === 'userInfo'">
            <div class="user-info-cell">
              <a-avatar :src="getUserAvatar(record.userAvatar)" :size="44" class="user-avatar">
                {{ record.userName?.charAt(0) || 'U' }}
              </a-avatar>
              <div class="user-info-text">
                <div class="user-name-row">
                  <span class="user-name">{{ record.userName || '未命名用户' }}</span>
                  <span class="user-account">{{ record.userAccount }}</span>
                </div>
                <div class="user-profile">{{ record.userProfile || '暂无简介' }}</div>
              </div>
            </div>
          </template>
          <template v-else-if="column.dataIndex === 'userRole'">
            <a-tag :color="record.userRole === 'admin' ? 'green' : 'blue'" class="soft-tag">
              {{ record.userRole === 'admin' ? '管理员' : '普通用户' }}
            </a-tag>
          </template>
          <template v-else-if="column.dataIndex === 'createTime'">
            {{ dayjs(record.createTime).format('YYYY-MM-DD HH:mm:ss') }}
          </template>
          <template v-else-if="column.key === 'action'">
            <a-space class="action-space">
              <a-button class="action-button credit-action-button" @click="openCreditModal(record)">
                <template #icon>
                  <PlusCircleOutlined />
                </template>
                积分
              </a-button>
              <a-popconfirm
                title="确定要删除这个用户吗？"
                :disabled="isDeletingUser(record.id)"
                @confirm="doDelete(record.id)"
              >
                <a-button
                  danger
                  ghost
                  class="action-button delete-action-button"
                  :loading="isDeletingUser(record.id)"
                >
                  <template #icon>
                    <DeleteOutlined />
                  </template>
                  删除
                </a-button>
              </a-popconfirm>
            </a-space>
          </template>
        </template>
      </a-table>
    </AdminDataPanel>

    <a-modal
      v-model:open="creditModalVisible"
      title="调整用户积分"
      :confirm-loading="adjustingCredit"
      @ok="submitCreditAdjust"
    >
      <a-form layout="vertical">
        <a-form-item label="用户">
          <a-input
            :value="selectedCreditUser?.userName || selectedCreditUser?.userAccount"
            disabled
          />
        </a-form-item>
        <a-form-item label="当前积分">
          <a-input :value="selectedCreditUser?.creditBalance ?? 0" disabled />
        </a-form-item>
        <a-form-item label="积分变动">
          <a-input-number
            v-model:value="creditForm.changeAmount"
            :precision="0"
            class="credit-input"
            placeholder="正数增加，负数扣减"
          />
        </a-form-item>
        <a-form-item label="调整原因">
          <a-textarea v-model:value="creditForm.remark" :rows="3" placeholder="请输入调整原因" />
        </a-form-item>
      </a-form>
    </a-modal>
  </AdminPageFrame>
</template>
<script lang="ts" setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { adjustUserCredit, deleteUser, listUserVoByPage } from '@/api/userController.ts'
import { message } from 'ant-design-vue'
import dayjs from 'dayjs'
import { DEFAULT_USER_AVATAR } from '@/constants/appDefaults'
import { DeleteOutlined, PlusCircleOutlined } from '@ant-design/icons-vue'
import AdminPageFrame from '@/components/admin/AdminPageFrame.vue'
import AdminFilterPanel from '@/components/admin/AdminFilterPanel.vue'
import AdminDataPanel from '@/components/admin/AdminDataPanel.vue'
import AdminSummaryBadge from '@/components/admin/AdminSummaryBadge.vue'
import { useLatestRequest } from '@/composables/useLatestRequest'

const columns = [
  { title: '用户信息', dataIndex: 'userInfo', width: 340 },
  { title: 'ID', dataIndex: 'id', width: 90 },
  { title: '用户角色', dataIndex: 'userRole', width: 120 },
  { title: '积分余额', dataIndex: 'creditBalance', width: 120 },
  { title: '创建时间', dataIndex: 'createTime', width: 180 },
  { title: '操作', key: 'action', width: 170 },
]

const data = ref<API.AdminUserVO[]>([])
const total = ref(0)
const loadError = ref('')
const creditModalVisible = ref(false)
const adjustingCredit = ref(false)
const selectedCreditUser = ref<API.AdminUserVO>()
const deletingUserIds = ref<Set<number>>(new Set())
const { loading, begin, isLatest, end } = useLatestRequest()

const creditForm = reactive<API.UserCreditAdjustRequest>({
  requestId: undefined,
  changeAmount: undefined,
  remark: '',
})
const searchParams = reactive<API.UserQueryRequest>({
  pageNum: 1,
  pageSize: 10,
})

const getUserAvatar = (userAvatar?: string) => userAvatar || DEFAULT_USER_AVATAR
const getUserRowKey = (record: API.AdminUserVO) => record.id ?? record.userAccount ?? ''
const isDeletingUser = (id?: number) => Boolean(id && deletingUserIds.value.has(id))

const fetchData = async () => {
  const requestId = begin()
  loadError.value = ''
  try {
    const res = await listUserVoByPage({ ...searchParams })
    if (!isLatest(requestId)) return

    if (res.data.code === 0 && res.data.data) {
      data.value = res.data.data.records ?? []
      total.value = res.data.data.totalRow ?? 0
      return
    }
    loadError.value = `获取用户列表失败：${res.data.message || '服务异常'}`
    message.error(loadError.value)
  } catch (error) {
    if (!isLatest(requestId)) return
    console.error('Failed to load users', error)
    loadError.value = '获取用户列表失败，请检查网络后重试'
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
  searchParams.userAccount = undefined
  searchParams.userName = undefined
  searchParams.pageNum = 1
  void fetchData()
}

const openCreditModal = (record: API.AdminUserVO) => {
  if (!record.id) {
    message.warning('用户 ID 无效')
    return
  }
  selectedCreditUser.value = record
  creditForm.requestId = crypto.randomUUID()
  creditForm.userId = record.id
  creditForm.changeAmount = undefined
  creditForm.remark = ''
  creditModalVisible.value = true
}

const submitCreditAdjust = async () => {
  if (!creditForm.userId || !creditForm.changeAmount) {
    message.warning('请输入非 0 的积分变动')
    return
  }
  const remark = creditForm.remark?.trim()
  if (!remark) {
    message.warning('请输入调整原因')
    return
  }

  adjustingCredit.value = true
  try {
    const res = await adjustUserCredit({
      requestId: creditForm.requestId,
      userId: creditForm.userId,
      changeAmount: creditForm.changeAmount,
      remark,
    })
    if (res.data.code !== 0) {
      message.error(`积分调整失败：${res.data.message || '服务异常'}`)
      return
    }
    message.success('积分调整成功')
    creditModalVisible.value = false
    await fetchData()
  } catch (error) {
    console.error('Failed to adjust user credit', error)
    message.error('积分调整失败，请检查网络后重试')
  } finally {
    adjustingCredit.value = false
  }
}

const doDelete = async (id?: number) => {
  if (!id || isDeletingUser(id)) return

  deletingUserIds.value = new Set(deletingUserIds.value).add(id)
  try {
    const res = await deleteUser({ id })
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
    console.error('Failed to delete user', error)
    message.error('删除失败，请检查网络后重试')
  } finally {
    const nextIds = new Set(deletingUserIds.value)
    nextIds.delete(id)
    deletingUserIds.value = nextIds
  }
}

onMounted(() => {
  void fetchData()
})
</script>

<style scoped>
.user-info-cell {
  display: flex;
  align-items: center;
  gap: 11px;
  min-width: 0;
}

.user-avatar {
  flex: 0 0 auto;
  border: 2px solid rgba(255, 255, 255, 0.88);
  box-shadow: 0 8px 20px rgba(63, 88, 120, 0.14);
}

.user-info-text {
  min-width: 0;
}

.user-name-row {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.user-name {
  overflow: hidden;
  color: var(--color-ink-strong);
  font-weight: 680;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.user-account {
  color: #91a0b2;
  font-size: 11px;
  white-space: nowrap;
}

.user-profile {
  max-width: 260px;
  margin-top: 3px;
  overflow: hidden;
  color: var(--color-ink-soft);
  font-size: 12px;
  line-height: 1.4;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.soft-tag {
  border: 0;
  border-radius: 999px;
}

.action-space {
  display: inline-flex;
  min-width: 148px;
}

.action-button {
  min-width: 66px;
  height: 32px;
  border-radius: 10px;
  font-weight: 650;
}

:deep(.action-button.ant-btn) {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 5px;
}

:deep(.credit-action-button.ant-btn) {
  border-color: transparent;
  color: #fff;
  background: linear-gradient(135deg, #267ff1, #49b6ee);
  box-shadow: 0 9px 20px rgba(47, 139, 255, 0.2);
}

:deep(.credit-action-button.ant-btn:hover),
:deep(.credit-action-button.ant-btn:focus) {
  color: #fff;
  transform: translateY(-1px);
}

:deep(.delete-action-button.ant-btn) {
  background: rgba(255, 255, 255, 0.82);
}

.credit-input {
  width: 100%;
}

:deep(.ant-modal-content),
:deep(.ant-input),
:deep(.ant-input-number),
:deep(.ant-btn) {
  border-radius: 12px;
}

@media (prefers-reduced-motion: reduce) {
  :deep(.credit-action-button.ant-btn:hover),
  :deep(.credit-action-button.ant-btn:focus) {
    transform: none;
  }
}
</style>
