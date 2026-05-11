import { createApp } from 'vue'
import { Button, Card, Cell, CellGroup, Col, ConfigProvider, Dialog, Empty, Field, Grid, GridItem, Icon, NavBar, NoticeBar, Popup, Row, Search, Step, Steps, Swipe, SwipeItem, Tab, Tabs, Tag } from 'vant'
import 'vant/lib/index.css'
import App from './App.vue'
import router from './router'
import { setupMock } from './mocks'
import './styles/mobile.css'

const app = createApp(App)

setupMock()

app.use(router)
app.use(ConfigProvider)
app.use(Button)
app.use(Card)
app.use(Cell)
app.use(CellGroup)
app.use(Col)
app.use(Dialog)
app.use(Empty)
app.use(Field)
app.use(Grid)
app.use(GridItem)
app.use(Icon)
app.use(NavBar)
app.use(NoticeBar)
app.use(Popup)
app.use(Row)
app.use(Search)
app.use(Step)
app.use(Steps)
app.use(Swipe)
app.use(SwipeItem)
app.use(Tab)
app.use(Tabs)
app.use(Tag)

app.mount('#app')
