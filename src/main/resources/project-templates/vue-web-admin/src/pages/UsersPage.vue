<template>
  <div class="users-page">
    <Card>
      <CardHeader class="flex flex-row items-center justify-between">
        <div>
          <CardTitle>用户管理</CardTitle>
          <CardDescription>管理系统用户和权限</CardDescription>
        </div>
        <Button @click="handleAdd">新增用户</Button>
      </CardHeader>

      <CardContent>
        <!-- Search Bar -->
        <div class="flex gap-4 mb-4">
          <Input v-model="searchParams.userAccount" placeholder="搜索账号" class="max-w-xs" />
          <Input v-model="searchParams.userName" placeholder="搜索用户名" class="max-w-xs" />
          <Button variant="outline" @click="handleSearch">搜索</Button>
          <Button variant="ghost" @click="handleReset">重置</Button>
        </div>

        <!-- Data Table -->
        <div class="rounded-md border">
          <table class="w-full caption-bottom text-sm">
            <thead class="[&_tr]:border-b">
              <tr class="border-b transition-colors hover:bg-muted/50">
                <th class="h-12 px-4 text-left align-middle font-medium text-muted-foreground">ID</th>
                <th class="h-12 px-4 text-left align-middle font-medium text-muted-foreground">账号</th>
                <th class="h-12 px-4 text-left align-middle font-medium text-muted-foreground">用户名</th>
                <th class="h-12 px-4 text-left align-middle font-medium text-muted-foreground">角色</th>
                <th class="h-12 px-4 text-left align-middle font-medium text-muted-foreground">创建时间</th>
                <th class="h-12 px-4 text-left align-middle font-medium text-muted-foreground">操作</th>
              </tr>
            </thead>
            <tbody class="[&_tr:last-child]:border-0">
              <tr v-for="row in tableData" :key="row.id" class="border-b transition-colors hover:bg-muted/50">
                <td class="p-4 align-middle">{{ row.id }}</td>
                <td class="p-4 align-middle">{{ row.userAccount }}</td>
                <td class="p-4 align-middle">{{ row.userName }}</td>
                <td class="p-4 align-middle">
                  <Badge :variant="row.userRole === 'admin' ? 'default' : 'secondary'">
                    {{ row.userRole === 'admin' ? '管理员' : '普通用户' }}
                  </Badge>
                </td>
                <td class="p-4 align-middle">{{ row.createTime }}</td>
                <td class="p-4 align-middle">
                  <div class="flex gap-2">
                    <Button variant="outline" size="sm" @click="handleEdit(row)">编辑</Button>
                    <Button variant="destructive" size="sm" @click="handleDelete(row.id)">删除</Button>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <!-- Pagination -->
        <div class="flex items-center justify-end space-x-2 py-4">
          <span class="text-sm text-muted-foreground">
            共 {{ pagination.total }} 条，第 {{ pagination.page }}/{{ totalPages }} 页
          </span>
          <Button variant="outline" size="sm" :disabled="pagination.page <= 1" @click="handlePageChange(pagination.page - 1)">
            上一页
          </Button>
          <Button variant="outline" size="sm" :disabled="pagination.page >= totalPages" @click="handlePageChange(pagination.page + 1)">
            下一页
          </Button>
        </div>
      </CardContent>
    </Card>

    <!-- Form Modal (simple) -->
    <Dialog v-model="showModal">
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{{ modalTitle }}</DialogTitle>
        </DialogHeader>
        <form @submit.prevent="handleConfirm" class="space-y-4">
          <div class="space-y-2">
            <Label for="edit-account">账号</Label>
            <Input id="edit-account" v-model="editForm.userAccount" required />
          </div>
          <div class="space-y-2">
            <Label for="edit-name">用户名</Label>
            <Input id="edit-name" v-model="editForm.userName" required />
          </div>
          <div class="space-y-2" v-if="!currentRow">
            <Label for="edit-password">密码</Label>
            <Input id="edit-password" v-model="editForm.userPassword" type="password" required />
          </div>
          <div class="space-y-2">
            <Label for="edit-role">角色</Label>
            <select id="edit-role" v-model="editForm.userRole" class="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm">
              <option value="user">普通用户</option>
              <option value="admin">管理员</option>
            </select>
          </div>
          <DialogFooter>
            <Button type="button" variant="outline" @click="showModal = false">取消</Button>
            <Button type="submit">确认</Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed } from 'vue'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Badge } from '@/components/ui/badge'
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { getUserList, createUser, updateUser, deleteUser } from '@/services/api'
import type { UserInfo } from '@/types'

const loading = ref(false)
const tableData = ref<UserInfo[]>([])
const showModal = ref(false)
const modalTitle = ref('新增用户')
const currentRow = ref<UserInfo | null>(null)

const pagination = reactive({
  page: 1,
  pageSize: 10,
  total: 0
})

const searchParams = reactive({
  userAccount: '',
  userName: ''
})

const editForm = reactive<{
  userAccount: string
  userName: string
  userPassword: string
  userRole: UserInfo['userRole']
}>({
  userAccount: '',
  userName: '',
  userPassword: '',
  userRole: 'user'
})

const totalPages = computed(() => Math.ceil(pagination.total / pagination.pageSize))

async function fetchData() {
  loading.value = true
  try {
    const res = await getUserList({
      page: pagination.page,
      pageSize: pagination.pageSize
    })
    tableData.value = res.data.list
    pagination.total = res.data.total
  } catch (error: any) {
    alert(error?.message || '加载失败')
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  pagination.page = 1
  fetchData()
}

function handleReset() {
  searchParams.userAccount = ''
  searchParams.userName = ''
  pagination.page = 1
  fetchData()
}

function handlePageChange(page: number) {
  pagination.page = page
  fetchData()
}

function handleAdd() {
  modalTitle.value = '新增用户'
  currentRow.value = null
  editForm.userAccount = ''
  editForm.userName = ''
  editForm.userPassword = ''
  editForm.userRole = 'user'
  showModal.value = true
}

function handleEdit(row: UserInfo) {
  modalTitle.value = '编辑用户'
  currentRow.value = { ...row }
  editForm.userAccount = row.userAccount
  editForm.userName = row.userName
  editForm.userPassword = ''
  editForm.userRole = row.userRole
  showModal.value = true
}

async function handleDelete(id: number) {
  if (!confirm('确定删除该用户？')) return
  try {
    await deleteUser(id)
    fetchData()
  } catch (error: any) {
    alert(error?.message || '删除失败')
  }
}

async function handleConfirm() {
  try {
    if (currentRow.value) {
      await updateUser({ ...currentRow.value, ...editForm })
    } else {
      await createUser(editForm as Partial<UserInfo>)
    }
    showModal.value = false
    fetchData()
  } catch (error: any) {
    alert(error?.message || '操作失败')
  }
}

fetchData()

// @AI_INJECT_VIEW
</script>

<style scoped>
.users-page {
  padding: 16px;
}
</style>
