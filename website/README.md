# Alba website

Astro + Tailwind CSS landing page for `alba.lvca.me`. Static output, no app backend and no website analytics. Includes support and privacy pages; the latter imports the repository's `PRIVACY.md` at build time. Contact: `alba@lvca.me`.

```sh
cd website
bun install --frozen-lockfile
bun run dev
bun run check
bun run build
```

Local preview: http://127.0.0.1:4328. Production files: `website/dist/`.

The design takes inspiration from CodeEdit's centred hero, compact header, product presentation and spacious feature sections: https://www.codeedit.app/ and https://github.com/CodeEditApp/codeedit.app. Implementation, copy and graphics are Alba's; no CodeEdit code or artwork was copied.

## Assets

The website uses Alba's approved SVG mark and actual demo-mode app captures. Photo provenance remains documented in [`public/images/demo-media-sources.json`](public/images/demo-media-sources.json); the website ships only compressed screen captures, not new stock photographs. The capture images were exported from the store screenshot worktree on October 1, 2026.

To refresh icon exports: `bun run assets`. To refresh screens too, pass a folder containing the original captures: `bun run assets -- /absolute/path/to/raw`. The input filenames are mapped in `scripts/prepare-assets.mjs`; the checked-in WebP files work without access to that folder.

## Publishing

GitHub Pages serves the generated static site from the public repository’s `gh-pages` branch. Source stays on `codex/alba-website`; this does not require merging the Android app branch. DNS: DNS-only CNAME `alba` to `thisislvca.github.io` in Cloudflare. The custom domain is configured in GitHub Pages before DNS is pointed there.

Run `bun run check` and `bun run build`, then publish the contents of `dist/` to `gh-pages`. Preserve `CNAME` and `.nojekyll`, which are included in `public/` and copied into every build. Never publish through the private archive remote (`origin`); use the public `thisislvca/alba` repository.

The website copy is written for launch. Download links use the Android application ID (`com.mannaworks.mela`) by default. Set `PUBLIC_PLAY_STORE_URL` if the confirmed listing differs; all store links share `src/config.ts`. No release version, waitlist, pricing, or donation destination is included.
