import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { resolve } from 'path';

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': resolve(__dirname, 'src'),
    },
  },
  server: {
    port: 3000,
    proxy: {
      '/ws': {
        target: 'ws://localhost:43595',
        ws: true,
      },
    },
  },
  build: {
    target: 'esnext',
    sourcemap: true,
  },
  define: {
    'process.env.SOLANA_NETWORK': JSON.stringify(process.env.SOLANA_NETWORK || 'devnet'),
  },
});
