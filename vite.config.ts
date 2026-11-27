import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    chunkSizeWarningLimit: 1500
  },
  server: {
    port: 3000,
    open: false,
    proxy: {
      '/yan-proxy': {
        target: 'https://yanhh3d.ee',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/yan-proxy/, ''),
        headers: {
          Referer: 'https://yanhh3d.ee/',
          Origin: 'https://yanhh3d.ee'
        }
      }
    }
  }
});
