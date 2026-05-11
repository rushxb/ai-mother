<template>
  <section class="panel">
    <div class="filter-bar">
      <input v-model="keyword" placeholder="搜索姓名、城市或角色" />
      <select v-model="status">
        <option value="">全部状态</option>
        <option>活跃</option>
        <option>待审核</option>
        <option>冻结</option>
      </select>
      <button class="primary" type="button" @click="createUser">新增用户</button>
    </div>
    <DataTable :columns="columns" :rows="filteredUsers">
      <template #status="{ row }">
        <span :class="['tag', row.status]">{{ row.status }}</span>
      </template>
      <template #amount="{ row }">¥{{ row.amount.toLocaleString() }}</template>
      <template #action="{ row }">
        <button type="button" @click="editUser(row)">编辑</button>
      </template>
    </DataTable>
  </section>
  <EditDrawer :open="drawerOpen" title="用户信息" :model="currentUser" @close="drawerOpen = false" @submit="saveUser" />
</template>

<script setup>
import { computed, ref } from 'vue'
import DataTable from '@/components/DataTable.vue'
import EditDrawer from '@/components/EditDrawer.vue'
import { users } from '@/data/adminData'

const keyword = ref('')
const status = ref('')
const drawerOpen = ref(false)
const currentUser = ref({})

const columns = [
  { title: 'ID', key: 'id' },
  { title: '姓名', key: 'name' },
  { title: '角色', key: 'role' },
  { title: '状态', key: 'status' },
  { title: '城市', key: 'city' },
  { title: '消费金额', key: 'amount' },
  { title: '最近访问', key: 'date' },
  { title: '操作', key: 'action' }
]

const filteredUsers = computed(() => {
  const key = keyword.value.trim()
  return users.filter((item) => {
    const matchedKeyword = !key || [item.name, item.city, item.role].some((field) => field.includes(key))
    const matchedStatus = !status.value || item.status === status.value
    return matchedKeyword && matchedStatus
  })
})

function createUser() {
  currentUser.value = { name: '', status: '待审核' }
  drawerOpen.value = true
}

function editUser(row) {
  currentUser.value = row
  drawerOpen.value = true
}

function saveUser() {
  drawerOpen.value = false
}
</script>
