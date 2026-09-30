import { createRequire } from 'node:module';
import { readFile, mkdir, writeFile } from 'node:fs/promises';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { renderTitles, renderAttribution, renderNote } from './titles.mjs';

const require = createRequire(import.meta.url);
const sharp = require('sharp');
const opentype = require('opentype.js');
const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const config = JSON.parse(await readFile(resolve(root, 'assets/store/screenshots.json'), 'utf8'));
// Render glyph outlines from the bundled font: no host font substitution.
const font = opentype.loadSync(resolve(root, 'tools/store-screenshots/fonts', config.style.font));
const { phone } = config.style;
const pillGradients = Object.entries(config.style.title.pills)
  .filter(([, tone]) => tone.gradient)
  .map(([name, tone]) => `<linearGradient id="pill-${name}" x1="0%" y1="0%" x2="100%" y2="30%">${tone.gradient.map((color, index) => `<stop offset="${index / (tone.gradient.length - 1)}" stop-color="${color}"/>`).join('')}</linearGradient>`)
  .join('');
const androidImage = await readFile(resolve(root, 'tools/store-screenshots/icons/android-head.svg'), 'utf8');
const phoneLeft = (config.width - phone.width) / 2;
const phoneHeight = Math.round(phone.width * config.captureHeight / config.captureWidth);
const input = resolve(process.argv[2] || resolve(root, '.artifacts/store-captures/raw'));
const output = resolve(process.argv[3] || resolve(root, 'assets/store', config.locale));
await mkdir(output, { recursive: true });
const thumbnails = [];
const filenameFor = card => card.filename || `${card.capture}.png`;
for (const [index, card] of config.cards.entries()) {
  if (!card.altText || [...card.altText].length > 140) throw new Error(`Provide alt text of at most 140 characters for ${card.capture}`);
  const source = sharp(resolve(input, `${card.capture}.png`));
  const raw = await source.metadata();
  if (raw.width !== config.captureWidth || raw.height !== config.captureHeight) throw new Error(`Capture ${card.capture} at ${config.captureWidth} x ${config.captureHeight} to preserve the real UI proportions`);
  const screen = await source.resize({ width: phone.width }).png().toBuffer();
  const header = renderTitles(font, card, config, androidImage);
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="${config.width}" height="${config.height}" viewBox="0 0 1080 1920">
    <defs>
      ${pillGradients}
      <radialGradient id="dawn" cx="50%" cy="100%" r="100%"><stop stop-color="#FFCA85"/><stop offset=".5" stop-color="#F3BED0"/><stop offset="1" stop-color="#D9D4F3"/></radialGradient>
      <linearGradient id="mist" x2="0" y2="1"><stop stop-color="#FAF7FD"/><stop offset=".55" stop-color="#FAF7FD" stop-opacity="0"/></linearGradient>
      <filter id="shadow" x="-20%" y="-10%" width="140%" height="130%"><feDropShadow dx="0" dy="18" stdDeviation="22" flood-color="#37205E" flood-opacity=".2"/></filter>
      <clipPath id="screen"><rect x="${phoneLeft}" y="${phone.top}" width="${phone.width}" height="${phoneHeight}" rx="48"/></clipPath>
    </defs>
    <rect width="1080" height="1920" fill="url(#dawn)"/>
    <rect width="1080" height="1920" fill="url(#mist)"/>
    ${header}
    ${renderNote(font, card, config)}
    <rect x="${phoneLeft - 6}" y="${phone.top - 6}" width="${phone.width + 12}" height="${phoneHeight + 12}" rx="54" fill="#FAFAFC" filter="url(#shadow)"/>
    <image x="${phoneLeft}" y="${phone.top}" width="${phone.width}" height="${phoneHeight}" href="data:image/png;base64,${screen.toString('base64')}" clip-path="url(#screen)"/>
    ${renderAttribution(font, card, config.width, config.height)}
  </svg>`;
  const filename = filenameFor(card);
  // Google Play accepts opaque RGB PNG screenshots. Keep the source capture unaltered.
  await sharp(Buffer.from(svg)).flatten({ background: '#FAF7FD' }).removeAlpha().png({ compressionLevel: 9 }).toFile(resolve(output, filename));
  const metadata = await sharp(resolve(output, filename)).metadata();
  if (metadata.width !== config.width || metadata.height !== config.height || metadata.hasAlpha) throw new Error(`Invalid store screenshot: ${filename}`);
  thumbnails.push({ input: await sharp(resolve(output, filename)).resize(270, 480).png().toBuffer(), left: index * 286, top: 0 });
}
await sharp({ create: { width: config.cards.length * 286 - 16, height: 480, channels: 3, background: '#FFF' } }).composite(thumbnails).png().toFile(resolve(output, 'preview.png'));
await writeFile(resolve(output, 'manifest.json'), JSON.stringify({ locale: config.locale, width: config.width, height: config.height, files: config.cards.map(filenameFor), readyFiles: config.cards.filter(c => c.releaseStatus !== 'planned').map(filenameFor), plannedFiles: config.cards.filter(c => c.releaseStatus === 'planned').map(filenameFor), altText: Object.fromEntries(config.cards.map(c => [filenameFor(c), c.altText])) }, null, 2) + '\n');
console.log(`Rendered ${config.cards.length} screenshots to ${output}`);
