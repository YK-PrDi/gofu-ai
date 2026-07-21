import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'
import path from 'node:path'

// 开发期：Vite dev server 在 5173，/api 反代到本地 Spring Boot(5021)。
// 上线期：npm run build → 产物落到 ../src/main/resources/static，由 Spring Boot 默认伺服(零配置)、jar打包自动含。
//   旧单文件版(index.html/batch.js/stores.js/data)被覆盖,可从 git 历史回滚。SPA 用 hash 路由,无需后端 fallback。
export default defineConfig({
  plugins: [
    vue(),
    AutoImport({ resolvers: [ElementPlusResolver()] }),
    Components({ resolvers: [ElementPlusResolver()] }),
  ],
  resolve: {
    alias: { '@': path.resolve(import.meta.dirname, 'src') },
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:5021',
        changeOrigin: true,
      },
    },
  },
  build: {
    outDir: path.resolve(import.meta.dirname, '../src/main/resources/static'),
    emptyOutDir: true,
  },
})
