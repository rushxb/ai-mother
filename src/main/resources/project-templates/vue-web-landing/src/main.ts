import { createApp } from 'vue'
import router from './router'
import App from './App.vue'
import { setupMock } from './mocks'
import './styles/landing.css'

// Setup mock in development
setupMock()

const app = createApp(App)

// Setup Router
app.use(router)

app.mount('#app')
