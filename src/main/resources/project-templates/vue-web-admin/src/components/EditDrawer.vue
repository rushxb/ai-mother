<template>
  <div v-if="open" class="drawer-mask" @click.self="$emit('close')">
    <form class="drawer" @submit.prevent="$emit('submit', form)">
      <header>
        <h2>{{ title }}</h2>
        <button type="button" @click="$emit('close')">关闭</button>
      </header>
      <label>
        名称
        <input v-model="form.name" placeholder="请输入名称" />
      </label>
      <label>
        状态
        <select v-model="form.status">
          <option>活跃</option>
          <option>待审核</option>
          <option>冻结</option>
        </select>
      </label>
      <label>
        备注
        <textarea v-model="form.note" rows="4" placeholder="补充说明" />
      </label>
      <footer>
        <button type="button" @click="$emit('close')">取消</button>
        <button class="primary" type="submit">保存</button>
      </footer>
    </form>
  </div>
</template>

<script setup>
import { reactive, watch } from 'vue'

const props = defineProps({
  open: Boolean,
  title: { type: String, default: '编辑' },
  model: { type: Object, default: () => ({}) }
})

defineEmits(['close', 'submit'])

const form = reactive({ name: '', status: '活跃', note: '' })

watch(
  () => props.model,
  (value) => Object.assign(form, { name: '', status: '活跃', note: '', ...value }),
  { immediate: true }
)
</script>
