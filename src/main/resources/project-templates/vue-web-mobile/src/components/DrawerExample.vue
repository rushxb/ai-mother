<template>
  <div class="drawer-example">
    <button type="button">打开抽屉</button>
    <slot />
  </div>
</template>

<script setup lang="ts">
</script>

<style scoped>
.drawer-example {
  padding: 16px;
  border-radius: 12px;
  background: #fff;
}
</style>
