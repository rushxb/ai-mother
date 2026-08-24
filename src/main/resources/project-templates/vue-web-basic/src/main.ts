import { createApp } from 'vue'
import { createPinia } from 'pinia'
import piniaPluginPersistedstate from 'pinia-plugin-persistedstate'
import router from './router'
import App from './App.vue'
import './styles/base.css'
import './styles/theme.css'

// Mock 仅在显式启用的开发环境按需加载，避免进入生产包。
async function setupDevelopmentMock(): Promise<void> {
  if (!import.meta.env.DEV || import.meta.env.VITE_USE_MOCK !== 'true') return
  const { setupMock } = await import('./mocks')
  setupMock()
}

void setupDevelopmentMock()

const app = createApp(App)

// Setup Pinia
const pinia = createPinia()
pinia.use(piniaPluginPersistedstate)
app.use(pinia)

// Setup Router
app.use(router)

app.mount('#app')
