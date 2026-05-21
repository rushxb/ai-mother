<template>
  <div class="forbidden-page">
    <n-result status="403" title="403" description="抱歉，您没有权限访问此页面">
      <template #footer>
        <n-button type="primary" @click="router.push('/')">
          返回首页
        </n-button>
      </template>
    </n-result>
  </div>
</template>

<script setup lang="ts">
import { useRouter } from 'vue-router'

const router = useRouter()
</script>

<style scoped>
.forbidden-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
}
</style>
