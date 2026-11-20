import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { env } from 'node:process'

const apiTarget = env.API_TARGET || 'http://127.0.0.1:8080'
// Tailscale Serve 转发时浏览器使用 <机器名>.<tailnet>.ts.net 访问；Vite 默认会拦截非本机 Host。
// 服务本身仍只监听 127.0.0.1，只有 tailnet 内设备可经 Tailscale 访问。
const allowedHosts = ['.ts.net', ...(env.EXTRA_ALLOWED_HOSTS?.split(',').filter(Boolean) ?? [])]
export default defineConfig({
  plugins: [vue()],
  server: { port: 5173, strictPort: true, allowedHosts, proxy: { '/api': apiTarget } },
  preview: { allowedHosts, proxy: { '/api': apiTarget } },
  build: {
    rollupOptions: {
      output: { manualChunks: { 'element-plus': ['element-plus'], 'vue-core': ['vue', 'vue-router'] } },
    },
  },
})
