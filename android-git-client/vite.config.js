import { defineConfig } from 'vite';

export default defineConfig({
  base: './',
  define: {
    global: 'globalThis',
  },
  resolve: {
    alias: {
      buffer: 'buffer/',
    },
  },
  build: {
    target: 'es2020',
    outDir: 'dist',
  },
  server: {
    host: true,
  },
});
