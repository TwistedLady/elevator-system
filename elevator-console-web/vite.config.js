// Dev proxy mirrors nginx.conf: relative /api and /actuator go to the local api (dev, HTTP
// :8080); in the cluster nginx reverse-proxies the same paths over HTTPS.
import { defineConfig } from 'vite';
import elmPlugin from 'vite-plugin-elm';

export default defineConfig({
  plugins: [elmPlugin()],
  build: {
    outDir: 'dist/elevator-console-web/browser',
    emptyOutDir: true,
  },
  server: {
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: true, secure: false },
      '/actuator': { target: 'http://localhost:8080', changeOrigin: true, secure: false },
    },
  },
});
