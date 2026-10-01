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

GitHub Pages deploys through `.github/workflows/website.yml`. `main` is the development branch; `release` is the production branch. Pushes to either branch check and build the website. Every push or merge to `release` automatically publishes the successful build to `https://alba.lvca.me`; `main` and pull requests never deploy. You can also run the workflow manually against `release`.

To publish approved changes, merge updated `main` into `release` and push `release` to the public `thisislvca/alba` repository. No version tag or GitHub Release is required. Failed checks/builds leave the current live website in place. GitHub Pages uses GitHub Actions as its source and the `github-pages` environment allows deployment from `release`.

DNS: DNS-only CNAME `alba` to `thisislvca.github.io` in Cloudflare. Keep `public/CNAME` and the configured Astro site origin at `alba.lvca.me`. The former generated `gh-pages` branch is historical and no longer controls deployment. Never publish through the private archive remote (`origin`); use the public repository.

The website copy is written for launch. Download links use the Android application ID (`com.mannaworks.mela`) by default. Set `PUBLIC_PLAY_STORE_URL` if the confirmed listing differs; all store links share `src/config.ts`. No release version, waitlist, pricing, or donation destination is included.
