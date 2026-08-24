import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import path from 'node:path'

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [react()],
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
