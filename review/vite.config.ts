import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// Two pages: the review at / and the client's practice page at /practice, each its own entry so the
// practice page loads none of the review's code.
export default defineConfig({
  plugins: [react()],
  server: { port: 5180 },
  build: {
    rollupOptions: {
      input: { main: 'index.html', practice: 'practice.html' },
      // The gate (middleware.ts) lets the client's key reach assets/r/ only, so the review's own code is
      // put in assets/a/ and never goes out to it. Shared chunks are code both pages load, so they are r/.
      output: {
        entryFileNames: (c) => (c.name === 'practice' ? 'assets/r/[name]-[hash].js' : 'assets/a/[name]-[hash].js'),
        chunkFileNames: 'assets/r/[name]-[hash].js',
        assetFileNames: (a) => ((a.names?.[0] ?? a.name ?? '').startsWith('practice') ? 'assets/r/[name]-[hash][extname]' : 'assets/a/[name]-[hash][extname]'),
      },
    },
  },
})
