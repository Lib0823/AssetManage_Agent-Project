import './assets/base.css'
import './assets/main.css'

import { createApp } from 'vue'
import App from './App.vue'
import router from './router'

// Vant components
import {
  Button,
  Tab,
  Tabs,
  NavBar,
  Icon,
  Cell,
  CellGroup,
  Field,
  Switch,
  Toast,
  Dialog,
  Loading,
  Popup,
  Tag,
  Badge,
  Skeleton,
  Empty,
  PullRefresh,
  List,
  DatePicker,
  Picker,
  Locale,
  Swipe,
  SwipeItem
} from 'vant'
import koKR from 'vant/es/locale/lang/ko-KR'
import 'vant/lib/index.css'

Locale.use('ko-KR', koKR)

// v-calendar
import VCalendar from 'v-calendar'
import 'v-calendar/style.css'

// Chart.js
import {
  Chart as ChartJS,
  Title,
  Tooltip,
  Legend,
  ArcElement,
  CategoryScale,
  LinearScale,
  BarElement,
  PointElement,
  LineElement
} from 'chart.js'

ChartJS.register(
  Title,
  Tooltip,
  Legend,
  ArcElement,
  CategoryScale,
  LinearScale,
  BarElement,
  PointElement,
  LineElement
)

const app = createApp(App)

// Use Vant components
app.use(Button)
app.use(Tab)
app.use(Tabs)
app.use(NavBar)
app.use(Icon)
app.use(Cell)
app.use(CellGroup)
app.use(Field)
app.use(Switch)
app.use(Toast)
app.use(Dialog)
app.use(Loading)
app.use(Popup)
app.use(Tag)
app.use(Badge)
app.use(Skeleton)
app.use(Empty)
app.use(PullRefresh)
app.use(List)
app.use(DatePicker)
app.use(Picker)
app.use(Swipe)
app.use(SwipeItem)

// Use v-calendar
app.use(VCalendar, {})

// Use router
app.use(router)

app.mount('#app')
