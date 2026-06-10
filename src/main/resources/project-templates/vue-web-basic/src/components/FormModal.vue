<template>
  <section class="form-modal">
    <h3>表单区域</h3>
    <slot />
  </section>
</template>

<script setup lang="ts">
</script>

<style scoped>
.form-modal {
  padding: 16px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
}
</style>
