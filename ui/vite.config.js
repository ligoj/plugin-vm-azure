import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'path'

// Pull the real host surface in for tests / dev. At runtime the browser
// resolves @ligoj/host via the import map in index.html; the build keeps it
// external. The host repo is a sibling of ligoj-plugins/.
const HOST_SRC = resolve(__dirname, '../../../ligoj/app-ui/src/main/webapp/src')

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@ligoj/host': resolve(HOST_SRC, 'host.js'),
      '@': HOST_SRC,
    },
    // CRITICAL. Without dedupe each side of the test picks its own
    // node_modules copy of pinia (etc.) and `setActivePinia` from the
    // test never reaches `useI18nStore` resolved via @ligoj/host.
    dedupe: ['vue', 'pinia', 'vue-router', 'vuetify'],
  },
  build: {
    lib: {
      entry: resolve(__dirname, 'src/index.js'),
      formats: ['es'],
      fileName: () => 'index.js',
    },
    rollupOptions: {
      // Shared deps come from the host via the import map in index.html.
      // Marking them external keeps the plugin small and ensures one
      // pinia / vue / vuetify runtime instance, not N copies.
      external: ['vue', 'vue-router', 'pinia', 'vuetify', '@ligoj/host'],
      output: { entryFileNames: 'index.js', assetFileNames: 'index.[ext]' },
    },
    outDir: resolve(__dirname, '../src/main/resources/META-INF/resources/webjars/vm-azure/vue'),
    emptyOutDir: true,
  },
  server: {
    port: 5177,
    proxy: {
      '/rest': { target: 'http://localhost:8080', changeOrigin: true },
      '/webjars': { target: 'http://localhost:8080', changeOrigin: true },
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['src/__tests__/setup.js'],
    exclude: ['node_modules/**', 'dist/**'],
    css: false,
    server: { deps: { inline: ['vuetify'] } },
  },
})
