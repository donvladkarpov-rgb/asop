import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import fs from 'fs'
import path from 'path'

const DEV_CERT_PATH = path.resolve(__dirname, 'dev-certs')
const HAS_DEV_CERTS = fs.existsSync(path.join(DEV_CERT_PATH, 'vite-key.pem')) && fs.existsSync(path.join(DEV_CERT_PATH, 'vite-cert.pem'))

export default defineConfig({
  plugins: [react()],
  server: HAS_DEV_CERTS ? {
    port: 3000,
    https: {
      key: fs.readFileSync(path.join(DEV_CERT_PATH, 'vite-key.pem')),
      cert: fs.readFileSync(path.join(DEV_CERT_PATH, 'vite-cert.pem')),
    },
    proxy: {
      '/api': {
        target: 'https://localhost:8080',
        changeOrigin: true,
        secure: false,
      },
      '/realms': {
        target: 'https://localhost:8080',
        changeOrigin: true,
        secure: false,
      },
    },
  } : {
    port: 3000,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      '/realms': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
