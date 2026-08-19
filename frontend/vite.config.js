import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'
import { fileURLToPath, URL } from 'node:url'

export default defineConfig({
  plugins: [
    vue(),

    // ------------------------------------------------------------------
    // 按需引入 Element Plus。**这是这个技术栈必做的一步优化。**
    //
    // 全量引入（main.js 里 app.use(ElementPlus)）会把整个组件库打进主包：
    // 实测 1,193 KB / gzip 384 KB。而这个项目实际只用了十几个组件。
    //
    // 这两个插件做的事：
    //   AutoImport  —— 自动 import ElMessage / ElMessageBox 这类**方法**
    //   Components  —— 扫模板里的 <el-xxx> 标签，只 import 用到的**组件**及其样式
    //
    // 代价是构建期多一次扫描，以及 IDE 需要 components.d.ts 才有类型提示
    // （插件会自动生成）。收益是主包能砍掉一大半。
    //
    // 注意：按需引入之后就**不要**在 main.js 里再 app.use(ElementPlus)，
    // 否则等于又把全量引回来了，插件白配。
    // ------------------------------------------------------------------
    AutoImport({ resolvers: [ElementPlusResolver()] }),
    Components({ resolvers: [ElementPlusResolver()] })
  ],

  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },

  server: {
    port: 5173,
    // ------------------------------------------------------------------
    // 开发代理：把后端接口转发到 Spring Boot（8090）
    //
    // 为什么必须有它：前端跑在 5173、后端在 8090，浏览器同源策略会拦住
    // 跨端口请求。两种解法——后端加 CORS，或前端用开发代理。
    // 选代理是因为它**不需要改后端**：CORS 配置只在开发期有用，
    // 上生产时前后端同域，那份配置就成了纯粹的技术债。
    //
    // 代理之后前端代码里一律写相对路径（/clinic/...），
    // 开发时被转发到 8090，构建后由 Spring Boot 同域托管，两种环境一套代码。
    // ------------------------------------------------------------------
    proxy: {
      '/clinic': { target: 'http://127.0.0.1:8090', changeOrigin: true },
      '/admin': { target: 'http://127.0.0.1:8090', changeOrigin: true },
      '/seckill': { target: 'http://127.0.0.1:8090', changeOrigin: true },
      '/control': { target: 'http://127.0.0.1:8090', changeOrigin: true },
      '/verify': { target: 'http://127.0.0.1:8090', changeOrigin: true },
      '/actuator': { target: 'http://127.0.0.1:8090', changeOrigin: true }
    }
  },

  build: {
    // ------------------------------------------------------------------
    // 直接构建到 Spring Boot 的静态目录，这样 mvn package 出来的 jar
    // 自带前端，一个 jar 就能跑整个应用，不用单独部署 nginx。
    // ------------------------------------------------------------------
    outDir: '../src/main/resources/static/app',
    emptyOutDir: true,
    sourcemap: false,      // 学习阶段可以开成 true 便于调试，但会让产物大好几倍

    rollupOptions: {
      output: {
        // 只把 vue 运行时拆出来 —— 它确实是「整个都要用」的。
        //
        // ⚠️ 这里踩过一个坑：最初还写了 `element: ['element-plus', ...]`，
        // 结果 element chunk 有 926 KB，按需引入好像白配了。
        //
        // 原因是**两个优化互相抵消**：manualChunks 里写包名等于告诉 Rollup
        // "把这个包整体打成一个 chunk"，而 unplugin 的按需引入依赖的正是
        // Rollup 的 tree-shaking 和自动分包 —— 手动指定包名把它覆盖掉了。
        //
        // 教训：manualChunks 只适合「确定整体都要用」的库（如 vue 运行时）。
        // 对按需引入的组件库，让 Rollup 自己决定怎么分，不要插手。
        manualChunks: {
          vue: ['vue', 'vue-router']
        }
      }
    }
  },

  base: './',

  // ------------------------------------------------------------------
  // Vitest。刻意用 environment: 'node' 而不是 jsdom：
  //
  // 要测的四个坑全部落在 composable 里，而它们是**纯逻辑**——退避算法、
  // 时钟偏移计算、倒计时边界、状态机映射，一个 DOM 节点都不需要。
  // 上 jsdom 会多装一个不小的依赖（而这台机器的代理不稳定），
  // 换来的只是能 mount 组件，而组件本身没什么好测的。
  //
  // 唯一需要处理的是 useGrab 依赖 `@/api/client`（里面用了 localStorage），
  // 用 vi.mock 把整个模块换掉就绕开了，比引入整个 DOM 环境干净得多。
  //
  // 什么时候该上 jsdom：要测「点击按钮后按钮变成 disabled」这类渲染行为时。
  // 那属于组件测试，和这里的逻辑测试是两层。
  // ------------------------------------------------------------------
  test: {
    environment: 'node',
    include: ['src/**/*.spec.js'],
    // 倒计时相关的测试要控制时间，统一用假定时器，避免真的等 1 秒
    clearMocks: true
  }
})
