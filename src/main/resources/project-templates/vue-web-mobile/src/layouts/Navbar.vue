<template>
  <van-nav-bar :title="title" />
</template>

<script setup>
defineProps({
  title: { type: String, default: '轻享生活' }
})
</script>
