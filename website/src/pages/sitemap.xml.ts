import type { APIRoute } from 'astro';
import { site } from '../config/site';

const paths = [
  '/', '/features/', '/features/scouting/', '/features/tactics/', '/features/recruitment/',
  '/features/staff/', '/features/ai-assistant/', '/how-it-works/', '/download/',
  '/getting-started/', '/faq/', '/privacy/', '/disclaimer/'
];

export const GET: APIRoute = () => {
  const urls = paths.map((path) => `  <url><loc>${new URL(path, site.origin)}</loc></url>`).join('\n');
  return new Response(`<?xml version="1.0" encoding="UTF-8"?>\n<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n${urls}\n</urlset>\n`, {
    headers: { 'Content-Type': 'application/xml; charset=utf-8' }
  });
};
