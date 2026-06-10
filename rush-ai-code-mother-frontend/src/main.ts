import { createApp } from 'vue'
import { createPinia } from 'pinia'

import App from './App.vue'
import router from './router'

import Antd from 'ant-design-vue'
import 'ant-design-vue/dist/reset.css'
import InspiraUI from '@/components/inspira-ui'

import '@/access'

const app = createApp(App)

app.use(createPinia())
app.use(router)
app.use(Antd)
app.use(InspiraUI)

app.mount('#app')
