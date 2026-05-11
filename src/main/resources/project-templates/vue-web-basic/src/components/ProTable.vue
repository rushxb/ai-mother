<template>
  <section class="pro-table">
    <div class="pro-table-toolbar">
      <input v-model="keyword" type="search" placeholder="搜索关键词" />
      <!-- @AI_INJECT_TABLE_ACTION -->
    </div>
    <div class="table-wrap">
      <table>
        <thead>
          <tr>
            <th v-for="column in columns" :key="column.key">{{ column.title }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in pagedRows" :key="row[rowKey]">
            <td v-for="column in columns" :key="column.key">{{ row[column.key] }}</td>
          </tr>
        </tbody>
      </table>
    </div>
    <footer class="pro-table-footer">
      <span>共 {{ filteredRows.length }} 条</span>
      <button type="button" :disabled="page === 1" @click="page -= 1">上一页</button>
      <strong>{{ page }} / {{ pageCount }}</strong>
      <button type="button" :disabled="page === pageCount" @click="page += 1">下一页</button>
    </footer>
  </section>
</template>

<script setup>
import { computed, ref, watch } from 'vue'

const props = defineProps({
  columns: { type: Array, default: () => [] },
  rows: { type: Array, default: () => [] },
  rowKey: { type: String, default: 'id' },
  pageSize: { type: Number, default: 8 }
})

const keyword = ref('')
const page = ref(1)

const filteredRows = computed(() => {
  const value = keyword.value.trim().toLowerCase()
  if (!value) return props.rows
  return props.rows.filter((row) => JSON.stringify(row).toLowerCase().includes(value))
})

const pageCount = computed(() => Math.max(1, Math.ceil(filteredRows.value.length / props.pageSize)))
const pagedRows = computed(() => filteredRows.value.slice((page.value - 1) * props.pageSize, page.value * props.pageSize))

watch(filteredRows, () => {
  page.value = 1
})
</script>
