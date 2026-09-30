import { createRequire } from 'node:module';
import { readFile, writeFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
const require = createRequire(import.meta.url);
const sharp = require('sharp');
const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const manifestPath = resolve(root, 'assets/store/demo-media.json');
const manifest = JSON.parse(await readFile(manifestPath, 'utf8'));
const selected = new Set(process.argv.slice(2).map(Number));
if (!selected.size) throw new Error('Pass the photo indices to import from demo-media.json');
for (const photo of manifest.photos.filter(p => selected.has(p.index))) {
  const url = new URL(photo.download);
  if (url.hostname !== 'images.pexels.com' || url.protocol !== 'https:') throw new Error('Expected a Pexels photo source');
  const response = await fetch(url);
  if (!response.ok) throw new Error(`Download failed for ${photo.index}: ${response.status}`);
  const bytes = await sharp(Buffer.from(await response.arrayBuffer())).rotate()
    .resize({ width: 960, height: 960, fit: 'inside', withoutEnlargement: true })
    .webp({ quality: 72, effort: 6 }).toBuffer();
  if (bytes.length > 160000) throw new Error(`Demo photo ${photo.index} exceeds its 160KB budget`);
  await writeFile(resolve(root, 'protocol-icloud-web/src/main/assets', photo.asset), bytes);
  const metadata = await sharp(bytes).metadata();
  Object.assign(photo, { width: metadata.width, height: metadata.height, bytes: bytes.length,
    sha256: createHash('sha256').update(bytes).digest('hex'),
    transform: 'Original framing; EXIF orientation applied; WebP quality 72; maximum edge 960px; metadata stripped' });
  console.log(`Photo ${photo.index}: ${metadata.width} x ${metadata.height}, ${bytes.length} bytes`);
}
manifest.bundledBytes = manifest.photos.reduce((sum, photo) => sum + photo.bytes + (photo.posterBytes || 0), 0);
await writeFile(manifestPath, JSON.stringify(manifest, null, 2) + '\n');
console.log(`Bundled demo media: ${manifest.bundledBytes} bytes`);
