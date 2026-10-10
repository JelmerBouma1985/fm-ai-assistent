# FM AI Assistent website

Static Astro website for [fmaiassistant.com](https://fmaiassistant.com/). It is isolated from the Spring Boot/Vaadin application in the repository root and deploys only `website/dist` to GitHub Pages.

## Local development

Node.js 24 is the CI baseline.

```bash
cd website
npm ci
npm run dev
npm run check
npm run build
npm run preview
```

`npm run build` creates the static site and runs `scripts/validate-build.mjs`. The validator checks mandatory routes, internal links and assets, unique metadata, canonicals, H1s, sitemap coverage and JSON-LD syntax.

## Site map and maintenance

- `/` — product overview
- `/features/` and five detailed feature pages
- `/how-it-works/` — snapshot and AI data flow
- `/download/` and `/getting-started/` — acquisition and setup
- `/faq/`, `/privacy/`, `/disclaimer/` — answers and trust

After every application release:

1. Recheck the root `README.md`, `RELEASE_NOTES.md`, actual release assets and supported operating systems.
2. Update claims, provider setup, screenshots and FAQ answers that changed.
3. Replace website screenshots only with reviewed genuine captures containing no secrets or private paths.
4. Run `npm ci`, `npm run check` and `npm run build` from `website/`.
5. Review at roughly 375, 768, 1366 and 1920 pixels before merging.

There is no analytics or tracking script. If analytics is considered later, make an explicit privacy decision first rather than adding it during routine maintenance.

## GitHub Pages launch checklist

These owner/admin actions cannot be completed by the static build:

- [ ] In the GitHub account Pages-domain settings, verify ownership of `fmaiassistant.com` with the TXT record GitHub provides. Retain that TXT record.
- [ ] In repository **Settings → Pages**, choose **GitHub Actions** as the publishing source and set the custom domain to exactly `fmaiassistant.com`.
- [ ] At the DNS provider, set the apex (`@`) to GitHub Pages' currently documented IPv4 addresses. Verify the values against [GitHub’s current custom-domain documentation](https://docs.github.com/en/pages/configuring-a-custom-domain-for-your-github-pages-site/managing-a-custom-domain-for-your-github-pages-site) before editing DNS:

  ```text
  185.199.108.153
  185.199.109.153
  185.199.110.153
  185.199.111.153
  ```

- [ ] Remove only DNS records that conflict at the apex. Keep email and ownership-verification records.
- [ ] Push/merge the website changes to `master` and confirm **Deploy FM AI Assistent website** completes successfully.
- [ ] Wait for the certificate, enable **Enforce HTTPS**, and verify the public response.
- [ ] Add `https://fmaiassistant.com/` to the repository’s **About → Website** field.

Verification commands after DNS and deployment:

```bash
dig +short fmaiassistant.com A
curl -I https://fmaiassistant.com/
curl -I https://fmaiassistant.com/robots.txt
curl -I https://fmaiassistant.com/sitemap.xml
```

The project uses the Pages settings for the custom domain; it does not commit a `CNAME` file. No separate hostname is configured by this project.

## Search launch and measurement

- [ ] Create a Google Search Console domain property, add its DNS TXT record and submit `https://fmaiassistant.com/sitemap.xml`.
- [ ] Verify/import the domain in Bing Webmaster Tools and submit the same sitemap.
- [ ] Inspect the homepage and key feature URLs after launch; submission does not guarantee indexing or rankings.
- [ ] Monitor indexed pages, crawl errors, impressions, clicks, CTR and landing pages in Search Console; monitor crawl/index errors and inbound links in Bing.
- [ ] If download conversion measurement becomes necessary, choose a privacy-respecting approach explicitly. Until then, use GitHub release metrics cautiously.
- [ ] Periodically run dated, reproducible FM26-related queries in AI search products and record whether the live site is cited. Results vary and no crawler setting guarantees citation.

Training-crawler policy is intentionally not specialized. If the owner later wants to disallow model-training crawlers, review current official bot identifiers and change them separately without blocking search bots such as `OAI-SearchBot` or `Claude-SearchBot`.
