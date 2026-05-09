<template>
  <div id="userManagePage" class="page-shell">
    <section class="page-head">
      <div>
        <h2 class="page-title">用户管理</h2>
      </div>
      <div class="page-summary">
        <span class="summary-label">当前总数</span>
        <span class="summary-value">{{ total }}</span>
      </div>
    </section>

    <a-card class="panel-card" :bordered="false">
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
    </a-card>

    <a-card class="panel-card table-card" :bordered="false">
      <a-table
        :columns="columns"
        :data-source="data"
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
            <a-popconfirm title="确定要删除这个用户吗？" @confirm="doDelete(record.id)">
              <a-button danger ghost class="action-button">删除</a-button>
            </a-popconfirm>
          </template>
        </template>
      </a-table>
    </a-card>
  </div>
</template>
<script lang="ts" setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { deleteUser, listUserVoByPage } from '@/api/userController.ts'
import { message } from 'ant-design-vue'
import dayjs from 'dayjs'
import { DEFAULT_USER_AVATAR } from '@/constants/appDefaults'

const columns = [
  {
    title: '用户信息',
    dataIndex: 'userInfo',
    width: 340,
  },
  {
    title: 'ID',
    dataIndex: 'id',
    width: 90,
  },
  {
    title: '用户角色',
    dataIndex: 'userRole',
    width: 120,
  },
  {
    title: '创建时间',
    dataIndex: 'createTime',
    width: 180,
  },
  {
    title: '操作',
    key: 'action',
    width: 100,
  },
]

// 展示的数据
const data = ref<API.UserVO[]>([])
const total = ref(0)
const getUserAvatar = (userAvatar?: string) => userAvatar || DEFAULT_USER_AVATAR

// 搜索条件
const searchParams = reactive<API.UserQueryRequest>({
  pageNum: 1,
  pageSize: 10,
})

const getUserRowKey = (record: API.UserVO) => record.id ?? ''

// 获取数据
const fetchData = async () => {
  const res = await listUserVoByPage({
    ...searchParams,
  })
  if (res.data.data) {
    data.value = res.data.data.records ?? []
    total.value = res.data.data.totalRow ?? 0
  } else {
    message.error('获取数据失败，' + res.data.message)
  }
}

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

// 表格分页变化时的操作
const doTableChange = (page: { current: number; pageSize: number }) => {
  searchParams.pageNum = page.current
  searchParams.pageSize = page.pageSize
  fetchData()
}

// 搜索数据
const doSearch = () => {
  // 重置页码
  searchParams.pageNum = 1
  fetchData()
}

const resetSearch = () => {
  searchParams.userAccount = undefined
  searchParams.userName = undefined
  searchParams.pageNum = 1
  fetchData()
}

// 删除数据
const doDelete = async (id: number) => {
  if (!id) {
    return
  }
  const res = await deleteUser({ id })
  if (res.data.code === 0) {
    message.success('删除成功')
    // 刷新数据
    fetchData()
  } else {
    message.error('删除失败')
  }
}

// 页面加载时请求一次
onMounted(() => {
  fetchData()
})
</script>

<style scoped>
#userManagePage {
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

.user-info-cell {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.user-avatar {
  flex: 0 0 auto;
  box-shadow: 0 8px 18px rgba(15, 23, 42, 0.12);
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
  font-weight: 600;
  color: #0f172a;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.user-account {
  color: #94a3b8;
  font-size: 12px;
  white-space: nowrap;
}

.user-profile {
  margin-top: 3px;
  color: #64748b;
  font-size: 12px;
  line-height: 1.4;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 260px;
}

.soft-tag {
  border-radius: 999px;
  border: 0;
}

.action-button {
  border-radius: 999px;
}

:deep(.ant-input),
:deep(.ant-select-selector),
:deep(.ant-btn) {
  border-radius: 999px !important;
}

:deep(.ant-form-item) {
  margin-bottom: 0;
}

@media (max-width: 768px) {
  #userManagePage {
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
