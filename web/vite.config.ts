import tailwindcss from '@tailwindcss/vite';
import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      '/api': process.env.RENDERWEAVE_API_URL ?? 'http://127.0.0.1:8080',
      '/actuator': process.env.RENDERWEAVE_API_URL ?? 'http://127.0.0.1:8080',
    },
  },
});
