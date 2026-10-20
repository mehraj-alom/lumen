import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// The console is served behind the same origin as the console backend in
// production, so the API base is a relative path by default and is
// overridden at build time for local development.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/v1': {
        target: process.env.LUMEN_CONSOLE_API ?? 'http://localhost:8080',
        changeOrigin: true,
        ws: true,
      },
    },
  },
  build: {
    outDir: 'dist',
    sourcemap: true,
    target: 'es2022',
  },
})
