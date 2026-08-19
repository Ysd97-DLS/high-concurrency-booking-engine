import { createApp } from 'vue'
import { createRouter, createWebHashHistory } from 'vue-router'
import App from './App.vue'
import './styles.css'

/**
 * 注意这里**没有** `app.use(ElementPlus)`。
 *
 * 组件和 ElMessage 这类方法都由 vite.config.js 里的 unplugin 自动按需引入，
 * 主包只会包含实际用到的十几个组件。如果在这里再 use 一次全量组件库，
 * 按需引入就白配了 —— 实测差别是主包 1,193 KB vs 拆分后 vendor 可缓存 + 业务包很小。
 *
 * 唯一还需要手动做的是**中文语言包**：日期选择器、分页器、确认框的默认文案是英文，
 * 而 unplugin 只处理组件本身，不处理 locale。
 */
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import { ElConfigProvider } from 'element-plus'

/**
 * 路由用 hash 模式（createWebHashHistory）而不是 history 模式。
 *
 * 原因：构建产物由 Spring Boot 当静态资源托管。history 模式下
 * 直接访问 /mine 会让 Spring 去找一个叫 mine 的资源，返回 404 ——
 * 需要在后端加一条「所有未匹配路径都转发到 index.html」的规则。
 * hash 模式把路径放在 # 后面，服务端只看到 /，不需要任何后端配合。
 *
 * 真实项目上 nginx 的话用 history 模式更好看，那时加一行 try_files 就行。
 */
const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/', redirect: '/book' },
    // 三个视图都用动态 import，路由级代码分割：
    // 用户进挂号页时不会下载运营看板的代码
    { path: '/book', name: 'book', component: () => import('./views/BookView.vue') },
    { path: '/mine', name: 'mine', component: () => import('./views/MineView.vue') },
    { path: '/admin', name: 'admin', component: () => import('./views/AdminView.vue') }
  ]
})

const app = createApp(App)
app.use(router)
// 全局注入中文 locale。App.vue 里用 <el-config-provider> 包住整个应用来生效。
app.provide('elLocale', zhCn)
app.component('ElConfigProvider', ElConfigProvider)
app.mount('#app')
