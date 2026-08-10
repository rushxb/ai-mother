import { ref, computed } from 'vue'
import { defineStore } from 'pinia'
import { safeLocalStorage } from '@/lib/safe-storage'

export const useAppStore = defineStore('app', () => {
  // State
  const theme = ref<'light' | 'dark'>('light')
  const locale = ref<'zh-CN' | 'en-US'>('zh-CN')
  const loading = ref(false)
  const sidebarCollapsed = ref(false)
  
  // Getters
  const isDark = computed(() => theme.value === 'dark')
  
  // Actions
  function toggleTheme() {
    theme.value = theme.value === 'light' ? 'dark' : 'light'
    document.documentElement.setAttribute('data-theme', theme.value)
  }
  
  function setLocale(newLocale: 'zh-CN' | 'en-US') {
    locale.value = newLocale
  }
  
  function setLoading(value: boolean) {
    loading.value = value
  }
  
  function toggleSidebar() {
    sidebarCollapsed.value = !sidebarCollapsed.value
  }
  
  // @AI_INJECT_STORE_ACTION
  
  return {
    theme,
    locale,
    loading,
    sidebarCollapsed,
    isDark,
    toggleTheme,
    setLocale,
    setLoading,
    toggleSidebar
  }
}, {
  persist: {
    key: 'app-store',
    storage: safeLocalStorage,
    paths: ['theme', 'locale', 'sidebarCollapsed']
  }
})
