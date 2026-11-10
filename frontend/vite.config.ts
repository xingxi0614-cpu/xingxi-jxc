import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'
import { fileURLToPath, URL } from 'node:url'
import pkg from './package.json'

const autoIndexTableResolver = {
  type: 'component' as const,
  resolve: (name: string) => {
    if (name === 'ElTable') {
      return {
        name: 'AutoIndexTable',
        from: '@/components/AutoIndexTable'
      }
    }
  }
}

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  return {
    plugins: [
      vue(),
      AutoImport({ resolvers: [ElementPlusResolver()] }),
      Components({ resolvers: [autoIndexTableResolver, ElementPlusResolver()] })
    ],
    resolve: {
      alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) }
    },
    define: {
      __APP_VERSION__: JSON.stringify(pkg.version)
    },
    server: {
      host: '0.0.0.0',
      port: 5173,
      proxy: {
        '/api': {
          target: process.env.SCM_DEV_PROXY || env.VITE_API_BASE || 'http://127.0.0.1:8080',
          changeOrigin: true
        }
      }
    },
    build: {
      target: 'es2020',
      sourcemap: false,
      chunkSizeWarningLimit: 1500,
      rollupOptions: {
        output: {
          // 首屏提速：把大依赖从主包拆出，浏览器可并行下载且版本不变时命中缓存
          manualChunks(id: string) {
            if (!id.includes('node_modules')) return
            if (id.includes('element-plus') || id.includes('@element-plus')) return 'element-plus'
            if (id.includes('echarts') || id.includes('zrender')) return 'echarts'
            if (id.includes('@tabler/icons-vue')) return 'icons'
            if (id.includes('/vue') || id.includes('@vue') || id.includes('pinia') || id.includes('vue-router')) return 'vue-vendor'
            return 'vendor'
          }
        }
      }
    }
  }
})
