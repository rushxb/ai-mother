import { createApp } from 'vue'
import App from './App.vue'
import router from './router'
import { setupMock } from './mocks'
import './styles/theme.css'
import './styles/base.css'

setupMock()

createApp(App).use(router).mount('#app')
