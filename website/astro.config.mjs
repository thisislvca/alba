import { defineConfig } from 'astro/config';
import tailwindcss from '@tailwindcss/vite';

export default defineConfig({
  site: 'https://alba.lvca.me',
  output: 'static',
  trailingSlash: 'always',
  vite: { plugins: [tailwindcss()] },
  devToolbar: { enabled: false },
});
