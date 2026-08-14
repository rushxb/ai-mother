<template>
  <div class="user-info">
    <a-avatar :src="userAvatar" :size="size">
      {{ user?.userName?.charAt(0) || 'U' }}
    </a-avatar>
    <span v-if="showName" class="user-name">{{ user?.userName || '未知用户' }}</span>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { DEFAULT_USER_AVATAR } from '@/constants/appDefaults'

interface Props {
  user?: API.PublicUserSummaryVO
  size?: number | 'small' | 'default' | 'large'
  showName?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  size: 'default',
  showName: true,
})

const userAvatar = computed(() => props.user?.userAvatar || DEFAULT_USER_AVATAR)
</script>

<style scoped>
.user-info {
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 8px;
}

.user-name {
  overflow: hidden;
  color: var(--color-ink, #2f4158);
  font-size: 13px;
  font-weight: 650;
  line-height: 1.4;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
