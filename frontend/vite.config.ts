import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { env } from 'node:process'

const apiTarget = env.API_TARGET || 'http://127.0.0.1:8080'
export default defineConfig({
  plugins: [vue()],
  server: { port: 5173, strictPort: true, proxy: { '/api': apiTarget } },
  preview: { proxy: { '/api': apiTarget } },
  build: {
    rollupOptions: {
      output: { manualChunks: { 'element-plus': ['element-plus'], 'vue-core': ['vue', 'vue-router'] } },
    },
  },
})
