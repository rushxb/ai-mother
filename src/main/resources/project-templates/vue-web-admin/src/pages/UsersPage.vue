<template>
  <div class="users-page">
    <n-card title="用户管理">
      <template #header-extra>
        <n-button type="primary" @click="handleAdd">
          新增用户
        </n-button>
      </template>
      
      <!-- Search Bar -->
      <SearchBar :filters="filters" @search="handleSearch" @reset="handleReset" />
      
      <!-- Data Table -->
      <n-data-table
        :columns="columns"
        :data="tableData"
        :loading="loading"
        :pagination="pagination"
        :bordered="false"
        :single-line="false"
        @update:page="handlePageChange"
        @update:page-size="handlePageSizeChange"
      />
    </n-card>
    
    <!-- Form Modal -->
    <FormModal
      v-model:show="showModal"
      :title="modalTitle"
      :fields="formFields"
      :initial-data="currentRow"
      @confirm="handleConfirm"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, h } from 'vue'
import { useMessage, NButton, NSpace, NPopconfirm } from 'naive-ui'
import type { DataTableColumns, PaginationProps } from 'naive-ui'
import SearchBar from '@/components/SearchBar.vue'
import FormModal from '@/components/FormModal.vue'
import type { FilterItem } from '@/components/SearchBar.vue'
import type { FormField } from '@/components/FormModal.vue'
import { getUserList, createUser, updateUser, deleteUser } from '@/services/api'
import type { UserInfo } from '@/types'

const message = useMessage()
const loading = ref(false)
const tableData = ref<UserInfo[]>([])
const showModal = ref(false)
const modalTitle = ref('新增用户')
const currentRow = ref<UserInfo | null>(null)

const pagination = reactive<PaginationProps>({
  page: 1,
  pageSize: 10,
  itemCount: 0,
  showSizePicker: true,
  pageSizes: [10, 20, 50]
})

const filters: FilterItem[] = [
  { key: 'userAccount', label: '账号', type: 'input', span: 6 },
  { key: 'userName', label: '用户名', type: 'input', span: 6 },
  { key: 'userRole', label: '角色', type: 'select', span: 6, options: [
    { label: '管理员', value: 'admin' },
    { label: '普通用户', value: 'user' }
  ]}
]

const formFields: FormField[] = [
  { key: 'userAccount', label: '账号', type: 'input', required: true, span: 12 },
  { key: 'userName', label: '用户名', type: 'input', required: true, span: 12 },
  { key: 'userPassword', label: '密码', type: 'input', required: true, span: 12 },
  { key: 'userRole', label: '角色', type: 'select', required: true, span: 12, options: [
    { label: '管理员', value: 'admin' },
    { label: '普通用户', value: 'user' }
  ]}
]

const columns: DataTableColumns<UserInfo> = [
  { title: 'ID', key: 'id', width: 80 },
  { title: '账号', key: 'userAccount', width: 120 },
  { title: '用户名', key: 'userName', width: 120 },
  { title: '角色', key: 'userRole', width: 100 },
  { title: '创建时间', key: 'createTime', width: 180 },
  {
    title: '操作',
    key: 'actions',
    width: 150,
    render(row) {
      return h(NSpace, {}, {
        default: () => [
          h(NButton, { size: 'small', onClick: () => handleEdit(row) }, { default: () => '编辑' }),
          h(NPopconfirm, { onPositiveClick: () => handleDelete(row.id) }, {
            trigger: () => h(NButton, { size: 'small', type: 'error' }, { default: () => '删除' }),
            default: () => '确定删除？'
          })
        ]
      })
    }
  }
]

async function fetchData() {
  loading.value = true
  try {
    const res = await getUserList({
      page: pagination.page || 1,
      pageSize: pagination.pageSize || 10
    })
    tableData.value = res.data.list
    pagination.itemCount = res.data.total
  } catch (error: any) {
    message.error(error.message)
  } finally {
    loading.value = false
  }
}

function handleSearch(params: Record<string, any>) {
  console.log('Search params:', params)
  fetchData()
}

function handleReset() {
  fetchData()
}

function handlePageChange(page: number) {
  pagination.page = page
  fetchData()
}

function handlePageSizeChange(pageSize: number) {
  pagination.pageSize = pageSize
  pagination.page = 1
  fetchData()
}

function handleAdd() {
  modalTitle.value = '新增用户'
  currentRow.value = null
  showModal.value = true
}

function handleEdit(row: UserInfo) {
  modalTitle.value = '编辑用户'
  currentRow.value = { ...row }
  showModal.value = true
}

async function handleDelete(id: number) {
  try {
    await deleteUser(id)
    message.success('删除成功')
    fetchData()
  } catch (error: any) {
    message.error(error.message)
  }
}

async function handleConfirm(data: Record<string, any>) {
  try {
    if (currentRow.value) {
      await updateUser({ ...currentRow.value, ...data })
      message.success('更新成功')
    } else {
      await createUser(data as Partial<UserInfo>)
      message.success('创建成功')
    }
    showModal.value = false
    fetchData()
  } catch (error: any) {
    message.error(error.message)
  }
}

// Initial fetch
fetchData()

// @AI_INJECT_VIEW
</script>

<style scoped>
.users-page {
  padding: 16px;
}
</style>
