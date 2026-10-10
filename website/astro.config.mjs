import { defineConfig } from 'astro/config';

export default defineConfig({
  site: 'https://fmaiassistant.com',
  output: 'static',
  trailingSlash: 'always',
  build: {
    format: 'directory'
  }
});
