import { mkdir, readFile, copyFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import sharp from 'sharp';
const root = resolve(import.meta.dirname, '../..');
const out = resolve(root, 'website/public/images');
await mkdir(out, { recursive: true });
const mark = await readFile(resolve(root, 'assets/brand/alba-mark.svg'));
await sharp(mark).resize(256).flatten({ background: '#fff' }).webp({ quality: 90 }).toFile(resolve(out, 'app-icon.webp'));
// An icon-only crop removes the square export padding without changing the mark.
await sharp(mark).extract({left:150, top:280, width:724, height:464}).resize(160).png().toFile(resolve(out, 'mark.png'));
await copyFile(resolve(root, 'assets/brand/alba-mark.svg'), resolve(out, 'favicon.svg'));
const captures = process.argv[2];
if (captures) {
  for (const [name, source] of Object.entries({library:'01-library', albums:'03-shared-albums', viewer:'02-viewer', share:'05-share'})) {
    await sharp(resolve(captures, `${source}.png`)).resize({width:810}).webp({quality:82}).toFile(resolve(out, `${name}.webp`));
  }
}
console.log('Prepared Alba website assets. Existing screen exports are kept unless a capture directory is supplied.');

const social = `<svg width="1200" height="630" xmlns="http://www.w3.org/2000/svg"><defs><linearGradient id="g"><stop stop-color="#d1adff"/><stop offset=".5" stop-color="#eea7c4"/><stop offset="1" stop-color="#ffc692"/></linearGradient></defs><rect width="1200" height="630" fill="#080809"/><text x="600" y="355" text-anchor="middle" fill="#f5f5f7" font-family="Helvetica,Arial,sans-serif" font-size="60" font-weight="600">Your iCloud photos.</text><text x="600" y="432" text-anchor="middle" fill="url(#g)" font-family="Helvetica,Arial,sans-serif" font-size="60" font-weight="600">Right at home on Android.</text><text x="600" y="505" text-anchor="middle" fill="#a0a0a8" font-family="Helvetica,Arial,sans-serif" font-size="24">Alba · Free &amp; open source</text></svg>`;
const socialIcon = await sharp(mark).resize(110).flatten({ background: '#fff' }).png().toBuffer();
await sharp(Buffer.from(social)).composite([{input:socialIcon,top:160,left:545}]).jpeg({quality:90}).toFile(resolve(out, 'social.jpg'));
