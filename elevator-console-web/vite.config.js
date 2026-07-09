import { defineConfig } from 'vite';
import elmPlugin from 'vite-plugin-elm';

// Dev proxy mirrors nginx.conf: the app's relative /api and /actuator URLs go to the local api
// (mvn/dev, plain HTTP on :8080). In the cluster nginx reverse-proxies the same paths over HTTPS.
export default defineConfig({
  plugins: [elmPlugin()],
  build: {
    // Match the path the Dockerfile copies into nginx and the pom's build comment.
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
