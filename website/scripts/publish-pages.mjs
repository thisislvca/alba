import { execFileSync } from 'node:child_process';
import { cpSync, existsSync, mkdtempSync, readdirSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';

const website = resolve(import.meta.dirname, '..');
const dist = join(website, 'dist');
if (!existsSync(join(dist, 'index.html')) || readFileSync(join(dist, 'CNAME'), 'utf8').trim() !== 'alba.lvca.me') {
  throw new Error('Build the website first. The output must include the alba.lvca.me CNAME.');
}
const checkout = mkdtempSync(join(tmpdir(), 'alba-pages-'));
const git = (...args) => execFileSync('git', ['-C', checkout, ...args], { stdio: 'inherit' });
try {
  // Always target the public website repository, never the private archive remote.
  execFileSync('git', ['-c', 'credential.helper=', '-c', 'credential.helper=!gh auth git-credential', 'clone', '--depth', '1', '--branch', 'gh-pages', 'https://github.com/thisislvca/alba.git', checkout], { stdio: 'inherit' });
  for (const entry of readdirSync(checkout)) {
    if (entry !== '.git') rmSync(join(checkout, entry), { recursive: true, force: true });
  }
  cpSync(dist, checkout, { recursive: true });
  git('add', '.');
  const changes = execFileSync('git', ['-C', checkout, 'status', '--porcelain'], { encoding: 'utf8' });
  if (changes.trim()) {
    git('commit', '-m', 'build(web): publish Alba website');
    git('-c', 'credential.helper=', '-c', 'credential.helper=!gh auth git-credential', 'push', 'origin', 'gh-pages');
  } else {
    console.log('The published branch already matches this build.');
  }
} finally {
  rmSync(checkout, { recursive: true, force: true });
}
