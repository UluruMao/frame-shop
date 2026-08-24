import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import path from 'node:path'

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [react()],
  // sockjs-client@1.x references the Node-only `global` global, which is undefined in
  // the browser → "Uncaught ReferenceError: global is not defined" crashes the whole
  // app on first render. Polyfill it to `globalThis` (the browser's global object).
  define: {
    global: 'globalThis',
  },
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  server: {
    port: 5173,
    // Proxy /api to the Spring Boot backend so dev avoids CORS.
    proxy: {
      '/api': {
        target: 'http://localhost:8081',
        changeOrigin: true,
        rewrite: (p) => p.replace(/^\/api/, ''),
      },
      // SockJS endpoint for the STOMP chat client (UC011). SockJS starts with an
      // HTTP handshake then upgrades to WebSocket, so the proxy must forward both
      // HTTP and the WS upgrade (ws: true).
      '/ws-chat': {
        target: 'http://localhost:8081',
        changeOrigin: true,
        ws: true,
      },
    },
  },
})
