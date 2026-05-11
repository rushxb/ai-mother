import { createApp } from 'vue'
import App from './App.vue'
import router from './router'
import { setupMock } from './mocks'
import './styles/landing.css'

setupMock()

createApp(App).use(router).mount('#app')
