<template>
  <n-modal
    v-model:show="visible"
    :title="title"
    :preset="preset"
    :positive-text="positiveText"
    :negative-text="negativeText"
    :loading="loading"
    :style="{ width: width || '600px' }"
    @positive-click="handleConfirm"
    @negative-click="handleCancel"
  >
    <n-form
      ref="formRef"
      :model="formData"
      :rules="rules"
      label-placement="left"
      label-width="auto"
      require-mark-placement="right-hanging"
    >
      <n-grid :cols="24" :x-gap="12">
        <n-gi
          v-for="field in fields"
          :key="field.key"
          :span="field.span || 24"
        >
          <n-form-item :label="field.label" :path="field.key">
            <!-- Input -->
            <n-input
              v-if="field.type === 'input'"
              v-model:value="formData[field.key]"
              :placeholder="field.placeholder || `请输入${field.label}`"
              :disabled="field.disabled"
            />
            
            <!-- Textarea -->
            <n-input
              v-else-if="field.type === 'textarea'"
              v-model:value="formData[field.key]"
              type="textarea"
              :placeholder="field.placeholder || `请输入${field.label}`"
              :rows="field.rows || 3"
              :disabled="field.disabled"
            />
            
            <!-- Select -->
            <n-select
              v-else-if="field.type === 'select'"
              v-model:value="formData[field.key]"
              :options="field.options || []"
              :placeholder="field.placeholder || `请选择${field.label}`"
              :disabled="field.disabled"
            />
            
            <!-- Radio -->
            <n-radio-group
              v-else-if="field.type === 'radio'"
              v-model:value="formData[field.key]"
              :disabled="field.disabled"
            >
              <n-radio
                v-for="option in field.options"
                :key="option.value"
                :value="option.value"
              >
                {{ option.label }}
              </n-radio>
            </n-radio-group>
            
            <!-- Switch -->
            <n-switch
              v-else-if="field.type === 'switch'"
              v-model:value="formData[field.key]"
              :disabled="field.disabled"
            />
            
            <!-- DatePicker -->
            <n-date-picker
              v-else-if="field.type === 'date'"
              v-model:formatted-value="formData[field.key]"
              type="date"
              :placeholder="field.placeholder || `请选择${field.label}`"
              :disabled="field.disabled"
            />
            
            <!-- Number -->
            <n-input-number
              v-else-if="field.type === 'number'"
              v-model:value="formData[field.key]"
              :placeholder="field.placeholder || `请输入${field.label}`"
              :min="field.min"
              :max="field.max"
              :disabled="field.disabled"
            />
          </n-form-item>
        </n-gi>
      </n-grid>
    </n-form>
    
    <!-- Custom content slot -->
    <slot />
  </n-modal>
</template>

<script setup lang="ts">
import { ref, reactive, watch } from 'vue'
import type { FormInst, FormRules } from 'naive-ui'

export interface FormField {
  key: string
  label: string
  type: 'input' | 'textarea' | 'select' | 'radio' | 'switch' | 'date' | 'number'
  placeholder?: string
  options?: Array<{ label: string; value: any }>
  span?: number
  rows?: number
  min?: number
  max?: number
  disabled?: boolean
  required?: boolean
  rules?: FormRules
}

const props = defineProps<{
  title: string
  fields: FormField[]
  width?: string
  preset?: 'dialog' | 'card'
  positiveText?: string
  negativeText?: string
  initialData?: Record<string, any>
}>()

const emit = defineEmits<{
  confirm: [data: Record<string, any>]
  cancel: []
}>()

const visible = defineModel<boolean>('show', { default: false })
const formRef = ref<FormInst | null>(null)
const loading = ref(false)

// Initialize form data
const formData = reactive<Record<string, any>>({})

// Watch for initial data changes
watch(
  () => props.initialData,
  (newData) => {
    if (newData) {
      Object.keys(newData).forEach((key) => {
        formData[key] = newData[key]
      })
    }
  },
  { immediate: true, deep: true }
)

// Watch for field changes to initialize defaults
watch(
  () => props.fields,
  (newFields) => {
    newFields.forEach((field) => {
      if (formData[field.key] === undefined) {
        formData[field.key] = null
      }
    })
  },
  { immediate: true }
)

// Generate validation rules
const rules = computed<FormRules>(() => {
  const result: FormRules = {}
  
  props.fields.forEach((field) => {
    if (field.required) {
      result[field.key] = {
        required: true,
        message: `请输入${field.label}`,
        trigger: ['input', 'blur']
      }
    }
    if (field.rules) {
      result[field.key] = [...(result[field.key] ? [result[field.key]] : []), ...field.rules]
    }
  })
  
  return result
})

async function handleConfirm() {
  try {
    await formRef.value?.validate()
    loading.value = true
    emit('confirm', { ...formData })
  } catch (error) {
    // Validation failed
  } finally {
    loading.value = false
  }
}

function handleCancel() {
  emit('cancel')
  visible.value = false
}

// @AI_INJECT_MODAL
</script>
