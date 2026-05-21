<template>
  <div class="search-bar">
    <n-form :model="formData" label-placement="left" label-width="auto">
      <n-grid :cols="24" :x-gap="12">
        <n-gi v-for="item in filters" :key="item.key" :span="item.span || 6">
          <n-form-item :label="item.label" :path="item.key">
            <!-- Input -->
            <n-input
              v-if="item.type === 'input'"
              v-model:value="formData[item.key]"
              :placeholder="item.placeholder || `请输入${item.label}`"
              clearable
            />
            
            <!-- Select -->
            <n-select
              v-else-if="item.type === 'select'"
              v-model:value="formData[item.key]"
              :options="item.options || []"
              :placeholder="item.placeholder || `请选择${item.label}`"
              clearable
            />
            
            <!-- DatePicker -->
            <n-date-picker
              v-else-if="item.type === 'date'"
              v-model:formatted-value="formData[item.key]"
              type="date"
              :placeholder="item.placeholder || `请选择${item.label}`"
              clearable
            />
            
            <!-- DateRange -->
            <n-date-picker
              v-else-if="item.type === 'daterange'"
              v-model:formatted-value="formData[item.key]"
              type="daterange"
              :placeholder="item.placeholder || `请选择${item.label}`"
              clearable
            />
          </n-form-item>
        </n-gi>
        
        <n-gi :span="6">
          <n-space>
            <n-button type="primary" @click="handleSearch">
              搜索
            </n-button>
            <n-button @click="handleReset">
              重置
            </n-button>
          </n-space>
        </n-gi>
      </n-grid>
    </n-form>
  </div>
</template>

<script setup lang="ts">
import { reactive } from 'vue'

export interface FilterItem {
  key: string
  label: string
  type: 'input' | 'select' | 'date' | 'daterange'
  placeholder?: string
  options?: Array<{ label: string; value: string | number }>
  span?: number
  defaultValue?: any
}

const props = defineProps<{
  filters: FilterItem[]
}>()

const emit = defineEmits<{
  search: [params: Record<string, any>]
  reset: []
}>()

// Initialize form data with default values
const formData = reactive<Record<string, any>>(
  props.filters.reduce((acc, item) => {
    acc[item.key] = item.defaultValue ?? null
    return acc
  }, {} as Record<string, any>)
)

function handleSearch() {
  // Remove null/empty values
  const params = Object.entries(formData).reduce((acc, [key, value]) => {
    if (value !== null && value !== undefined && value !== '') {
      acc[key] = value
    }
    return acc
  }, {} as Record<string, any>)
  
  emit('search', params)
}

function handleReset() {
  props.filters.forEach((item) => {
    formData[item.key] = item.defaultValue ?? null
  })
  emit('reset')
}

// @AI_INJECT_FILTER
</script>

<style scoped>
.search-bar {
  padding: 16px;
  background: var(--surface);
  border-radius: var(--radius);
  margin-bottom: 16px;
}
</style>
