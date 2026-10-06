import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const target = env.API_PROXY_TARGET || 'http://127.0.0.1:8080'
  return {
    plugins: [vue()],
    base: '/app/',
    server: {
      port: 5173,
      strictPort: true,
      proxy: {
        '/api': { target, changeOrigin: true },
        '/admin': { target, changeOrigin: true },
        '/swagger-ui': { target, changeOrigin: true },
        '/v3/api-docs': { target, changeOrigin: true },
      },
    },
    build: { outDir: 'dist', sourcemap: false },
  }
})
