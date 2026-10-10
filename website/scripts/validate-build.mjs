import { access, readFile, readdir } from 'node:fs/promises';
import { join, relative, resolve } from 'node:path';

const root = resolve(import.meta.dirname, '..');
const dist = join(root, 'dist');
const origin = 'https://fmaiassistant.com';
const expected = [
  '/', '/features/', '/features/scouting/', '/features/tactics/', '/features/recruitment/',
  '/features/staff/', '/features/ai-assistant/', '/how-it-works/', '/download/',
  '/getting-started/', '/faq/', '/privacy/', '/disclaimer/'
];
const errors = [];
const titles = new Map();
const descriptions = new Map();

const htmlPath = (route) => route === '/' ? join(dist, 'index.html') : join(dist, route.slice(1), 'index.html');
const exists = async (path) => access(path).then(() => true, () => false);
const attr = (html, pattern) => html.match(pattern)?.[1]?.trim();

for (const route of expected) {
  const file = htmlPath(route);
  if (!(await exists(file))) {
    errors.push(`Missing route output: ${route}`);
    continue;
  }
  const html = await readFile(file, 'utf8');
  const title = attr(html, /<title>([^<]+)<\/title>/i);
  const description = attr(html, /<meta\s+name="description"\s+content="([^"]+)"/i);
  const canonicals = [...html.matchAll(/<link\s+rel="canonical"\s+href="([^"]+)"/gi)].map((match) => match[1]);
  const h1s = [...html.matchAll(/<h1(?:\s[^>]*)?>/gi)];
  const expectedCanonical = `${origin}${route}`;
  if (!title) errors.push(`${route}: missing title`);
  if (!description) errors.push(`${route}: missing description`);
  if (title && titles.has(title)) errors.push(`${route}: duplicate title also used by ${titles.get(title)}`);
  if (description && descriptions.has(description)) errors.push(`${route}: duplicate description also used by ${descriptions.get(description)}`);
  if (title) titles.set(title, route);
  if (description) descriptions.set(description, route);
  if (h1s.length !== 1) errors.push(`${route}: expected one H1, found ${h1s.length}`);
  if (canonicals.length !== 1 || canonicals[0] !== expectedCanonical) errors.push(`${route}: canonical must be ${expectedCanonical}`);
  if (/noindex/i.test(html)) errors.push(`${route}: indexable route contains noindex`);
  for (const script of html.matchAll(/<script\s+type="application\/ld\+json"[^>]*>([\s\S]*?)<\/script>/gi)) {
    try { JSON.parse(script[1]); } catch (error) { errors.push(`${route}: invalid JSON-LD (${error.message})`); }
  }
}

const htmlFiles = [];
const walk = async (directory) => {
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) await walk(path);
    else if (entry.name.endsWith('.html')) htmlFiles.push(path);
  }
};
await walk(dist);

for (const file of htmlFiles) {
  const html = await readFile(file, 'utf8');
  const label = relative(dist, file);
  if (/lorem ipsum|\bTODO\b|href="#"/i.test(html)) errors.push(`${label}: placeholder content found`);
  const references = [...html.matchAll(/(?:href|src)="([^"]+)"/gi)].map((match) => match[1]);
  for (const reference of references) {
    if (/^(?:https?:|mailto:|data:)/.test(reference) || reference.startsWith('#')) continue;
    const clean = reference.split(/[?#]/)[0];
    if (!clean.startsWith('/')) continue;
    const target = clean.endsWith('/') ? join(dist, clean, 'index.html') : join(dist, clean);
    if (!(await exists(target))) errors.push(`${label}: missing local reference ${reference}`);
  }
}

for (const required of ['robots.txt', 'sitemap.xml', 'favicon.svg', 'images/og-image.png']) {
  if (!(await exists(join(dist, required)))) errors.push(`Missing build asset: ${required}`);
}

if (await exists(join(dist, 'sitemap.xml'))) {
  const sitemap = await readFile(join(dist, 'sitemap.xml'), 'utf8');
  for (const route of expected) if (!sitemap.includes(`<loc>${origin}${route}</loc>`)) errors.push(`Sitemap missing ${route}`);
  if (sitemap.includes('/404/')) errors.push('Sitemap must not contain the 404 route');
}

if (errors.length) {
  console.error(`Website validation failed with ${errors.length} error(s):\n- ${errors.join('\n- ')}`);
  process.exit(1);
}
console.log(`Website validation passed: ${expected.length} routes, ${htmlFiles.length} HTML files, unique metadata, valid JSON-LD and local references.`);
