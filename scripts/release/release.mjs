import { execFileSync } from 'node:child_process';
import { appendFileSync, copyFileSync, existsSync, mkdirSync, readFileSync, writeFileSync, rmSync, readdirSync, lstatSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

export const packageName = 'me.lvca.alba';
export function releaseVersion(version, runNumber, offset = '1000') {
  if (!/^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/.test(version)) throw new Error('Version must be major.minor.patch, for example 0.2.0.');
  if (!/^\d+$/.test(String(offset)) || !/^[1-9]\d*$/.test(String(runNumber))) throw new Error('Invalid build counter.');
  const versionCode = Number(offset) + Number(runNumber);
  if (!Number.isSafeInteger(versionCode) || versionCode < 2 || versionCode > 2100000000) throw new Error('Build number is outside the Android range.');
  return { version, versionCode, tag: `app/v${version}-build.${versionCode}` };
}
export function validateNotes(notes) {
  if (Array.from(notes).length > 500) throw new Error('Play release notes must be 500 characters or fewer.');
  return notes.trim();
}
export function draftNotes(commits) {
  const relevant = commits.filter(c => /^(feat|fix|perf)(\([^)]*\))?!?:/.test(c.subject) && !/^(feat|fix|perf)\((web|website)\)/.test(c.subject));
  return relevant.length ? relevant.map(c => `- ${c.subject.replace(/^(feat|fix|perf)(\([^)]*\))?!?:\s*/, '')} (${c.sha.slice(0, 7)})`).join('\n') : 'Review the changes below and write a short summary before publishing.';
}
export const retiredCertificate = 'afe94e42da9e5e3a6af30bbdce5dd806b9ac32a0e2e9193ff43de5401d787f5b';
export function approvedCertificate(value) {
  const fingerprint = String(value || '').replaceAll(':', '').toLowerCase();
  if (!/^[a-f0-9]{64}$/.test(fingerprint) || fingerprint === retiredCertificate) throw new Error('Missing, invalid or retired upload certificate.');
  return fingerprint;
}
export function validateManifest(m, signed = true) {
  const expected = releaseVersion(m.version, '1', String(m.versionCode - 1));
  if (m.schema !== 2 || m.packageName !== packageName || m.tag !== expected.tag || !/^[a-f0-9]{40}$/.test(m.commit) || m.signed !== signed) throw new Error('Invalid release manifest.');
  const files = Object.keys(m.files || {});
  if (!files.includes('alba.aab') || files.some(name => !['alba.aab', 'mapping.txt'].includes(name))) throw new Error('Unexpected release assets.');
  for (const digest of Object.values(m.files)) if (!/^[a-f0-9]{64}$/.test(digest)) throw new Error('Invalid release asset hash.');
  if (signed) approvedCertificate(m.uploadCertificate);
  return m;
}
const env = process.env;
const out = resolve('.artifacts/release');
const credentials = join(env.RUNNER_TEMP || '.artifacts', 'alba-release-credentials');
const run = (exe, args, options = {}) => {
  try { return execFileSync(exe, args, { encoding: 'utf8', stdio: 'pipe', ...options }); }
  catch { throw new Error(`${exe} failed. Command output was suppressed to avoid exposing credentials.`); }
};
const git = (...args) => run('git', args).trim();
const gh = (...args) => run('gh', args).trim();
const json = file => JSON.parse(readFileSync(file, 'utf8'));
const save = (file, value) => writeFileSync(file, JSON.stringify(value, null, 2) + '\n');
const hash = file => createHash('sha256').update(readFileSync(file)).digest('hex');
function requireEnv(names) {
  const missing = names.filter(name => !env[name]);
  if (missing.length) throw new Error(`Configure GitHub secrets/variables: ${missing.join(', ')}. See RELEASING.md.`);
}
function output(name, value) { if (env.GITHUB_OUTPUT) appendFileSync(env.GITHUB_OUTPUT, `${name}=${value}\n`); }

function prepare() {
  if (env.GITHUB_REPOSITORY !== 'thisislvca/alba' || env.GITHUB_REF !== 'refs/heads/main') throw new Error('App releases must run from public Alba main.');
  const v = releaseVersion(env.APP_VERSION, env.GITHUB_RUN_NUMBER, env.APP_VERSION_CODE_OFFSET || '1000');
  const notes = validateNotes(env.APP_NOTES || '');
  const commit = git('rev-parse', 'HEAD');
  if (commit !== env.GITHUB_SHA) throw new Error('Checkout does not match the selected workflow commit.');
  if (git('tag', '--list', v.tag)) throw new Error('This build tag already exists. Start a new workflow run for a new candidate.');
  if (env.DRY_RUN !== 'true') {
    if (env.UPLOAD_TO_PLAY === 'true') {
      if (!notes) throw new Error('Provide short, human-written release notes when uploading to Play.');
    }
    const runs = JSON.parse(gh('api', `repos/thisislvca/alba/actions/workflows/ci.yml/runs?head_sha=${commit}&event=push&per_page=20`)).workflow_runs;
    const latest = runs.find(r => r.head_sha === commit && r.head_branch === 'main');
    if (latest?.conclusion !== 'success') throw new Error('Android CI must pass on this exact main commit before a signed release can be prepared.');
  }
  mkdirSync(out, { recursive: true });
  const previous = git('describe', '--tags', '--match', 'app/v*', '--abbrev=0', '--always', 'HEAD');
  const range = previous.startsWith('app/v') ? `${previous}..HEAD` : 'HEAD';
  const commits = git('log', '--format=%H%x09%s', range, '--', 'app', 'engine', 'protocol-icloud-web', 'gradle', 'gradle.properties', 'build.gradle.kts', 'settings.gradle.kts').split('\n').filter(Boolean).map(line => { const [sha, ...subject] = line.split('\t'); return { sha, subject: subject.join('\t') }; });
  save(join(out, 'release.json'), { schema: 2, packageName, ...v, commit, runUrl: `https://github.com/thisislvca/alba/actions/runs/${env.GITHUB_RUN_ID}`, signed: false, notes, files: {} });
  writeFileSync(join(out, 'NOTES.md'), `${notes || draftNotes(commits)}\n\nSource: https://github.com/thisislvca/alba/commit/${commit}\nBuild: ${v.versionCode}\n\n${previous.startsWith('app/v') ? `Changes: https://github.com/thisislvca/alba/compare/${previous}...${commit}` : 'First app release candidate.'}\n`);
  output('version_code', v.versionCode); output('tag', v.tag);
}

function verifyFiles(m) {
  for (const [name, digest] of Object.entries(m.files)) {
    const path = join(out, name);
    if (!lstatSync(path).isFile() || hash(path) !== digest) throw new Error('Release asset hash mismatch.');
  }
}
function writeHashes(m) {
  save(join(out, 'release.json'), m);
  writeFileSync(join(out, 'SHA256SUMS'), Object.entries(m.files).map(([name, digest]) => `${digest}  ${name}`).join('\n') + '\n');
}
function checkFiles() {
  const allowed = ['alba.aab', 'mapping.txt', 'release.json', 'SHA256SUMS', 'NOTES.md'];
  for (const name of readdirSync(out)) {
    if (!allowed.includes(name) || !lstatSync(join(out, name)).isFile()) throw new Error('Unexpected file in release artifacts.');
  }
  for (const name of ['release.json', 'NOTES.md', 'mapping.txt']) {
    if (existsSync(join(out, name)) && /-----BEGIN (?:[A-Z ]*PRIVATE KEY)-----|"type"\s*:\s*"service_account"/.test(readFileSync(join(out, name), 'utf8'))) throw new Error('Credential material detected in release metadata.');
  }
}
function checkSource(m) {
  if (env.GITHUB_REPOSITORY !== 'thisislvca/alba' || env.GITHUB_REF !== 'refs/heads/main' || m.commit !== env.GITHUB_SHA || git('rev-parse', 'HEAD') !== m.commit) throw new Error('Candidate must match this main workflow commit.');
}

function packageBuild() {
  const m = json(join(out, 'release.json'));
  checkSource(m);
  const apk = 'app/build/outputs/apk/release/app-release-unsigned.apk';
  const aab = 'app/build/outputs/bundle/release/app-release.aab';
  const tools = join(env.ANDROID_HOME || env.ANDROID_SDK_ROOT, 'build-tools', '37.0.0');
  const badging = run(join(tools, 'aapt'), ['dump', 'badging', apk]);
  const match = badging.match(/package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'/);
  if (!match || match[1] !== m.packageName || Number(match[2]) !== m.versionCode || match[3] !== m.version) throw new Error('APK identity/version does not match the release manifest.');
  const contents = run('jar', ['tf', aab]);
  if (/META-INF\/[^/]+\.(SF|RSA|DSA|EC)/i.test(contents)) throw new Error('Build worker must produce an unsigned bundle.');
  copyFileSync(aab, join(out, 'alba.aab'));
  m.files['alba.aab'] = hash(join(out, 'alba.aab'));
  const mapping = 'app/build/outputs/mapping/release/mapping.txt';
  if (existsSync(mapping)) { copyFileSync(mapping, join(out, 'mapping.txt')); m.files['mapping.txt'] = hash(join(out, 'mapping.txt')); }
  m.signed = false;
  validateManifest(m, false);
  writeHashes(m);
  checkFiles();
}

function signBundle() {
  requireEnv(['ALBA_UPLOAD_KEYSTORE_BASE64', 'ALBA_KEYSTORE_PASSWORD', 'ALBA_KEY_ALIAS', 'ALBA_UPLOAD_CERT_SHA256']);
  const expected = approvedCertificate(env.ALBA_UPLOAD_CERT_SHA256);
  const m = validateManifest(json(join(out, 'release.json')), false);
  checkSource(m);
  verifyFiles(m);
  checkFiles();
  mkdirSync(credentials, { recursive: true, mode: 0o700 });
  const keystore = join(credentials, 'upload.keystore');
  try {
    writeFileSync(keystore, Buffer.from(env.ALBA_UPLOAD_KEYSTORE_BASE64, 'base64'), { mode: 0o600, flag: 'wx' });
    const cert = run('keytool', ['-exportcert', '-keystore', keystore, '-alias', env.ALBA_KEY_ALIAS, '-storepass:env', 'ALBA_KEYSTORE_PASSWORD'], { encoding: 'buffer' });
    const actual = approvedCertificate(createHash('sha256').update(cert).digest('hex'));
    if (actual !== expected) throw new Error('Upload key does not match the approved certificate.');
    const bundle = join(out, 'alba.aab');
    run('jarsigner', ['-keystore', keystore, '-storepass:env', 'ALBA_KEYSTORE_PASSWORD', '-keypass:env', 'ALBA_KEYSTORE_PASSWORD', '-digestalg', 'SHA-256', '-sigalg', 'SHA256withRSA', bundle, env.ALBA_KEY_ALIAS]);
    verifyBundle(expected);
    m.files['alba.aab'] = hash(bundle);
    m.signed = true;
    m.uploadCertificate = expected;
    writeHashes(m);
  } finally {
    rmSync(credentials, { recursive: true, force: true });
  }
  checkFiles();
}
function verifyBundle(expected) {
  const bundle = join(out, 'alba.aab');
  const verified = run('jarsigner', ['-verify', bundle]);
  if (!verified.includes('jar verified.')) throw new Error('Bundle signature is invalid.');
  const cert = run('keytool', ['-printcert', '-jarfile', bundle]);
  const actual = cert.match(/SHA256:\s*([A-F0-9:]+)/i)?.[1];
  if (approvedCertificate(actual) !== expected) throw new Error('Bundle certificate mismatch.');
}
function publishCandidate() {
  const m = validateManifest(json(join(out, 'release.json')));
  checkSource(m);
  verifyFiles(m);
  checkFiles();
  git('config', 'user.name', 'Alba releases'); git('config', 'user.email', 'thisislvca@users.noreply.github.com');
  git('-c', 'tag.gpgSign=false', 'tag', '-a', m.tag, m.commit, '-m', `Alba ${m.version}, build ${m.versionCode}`);
  git('-c', 'credential.helper=', '-c', 'credential.helper=!gh auth git-credential', 'push', 'https://github.com/thisislvca/alba.git', `refs/tags/${m.tag}`);
  gh('release', 'create', m.tag, '--repo', 'thisislvca/alba', '--verify-tag', '--draft', '--prerelease', '--title', `Alba ${m.version} (build ${m.versionCode})`, '--notes-file', join(out, 'NOTES.md'), ...Object.keys(m.files).map(name => join(out, name)), join(out, 'release.json'), join(out, 'SHA256SUMS'));
  console.log(`Draft release created: ${m.tag}`);
}
function verifyProvenance(m) {
  for (const name of ['alba.aab', 'release.json']) {
    gh('attestation', 'verify', join(out, name), '--repo', 'thisislvca/alba', '--signer-workflow', 'thisislvca/alba/.github/workflows/prepare-app-release.yml', '--source-ref', 'refs/heads/main', '--source-digest', m.commit, '--deny-self-hosted-runners');
  }
}
function verifyCandidate() {
  if (!/^app\/v\d+\.\d+\.\d+-build\.\d+$/.test(env.APP_RELEASE_TAG || '')) throw new Error('Select an app/vX.Y.Z-build.N tag.');
  mkdirSync(out, { recursive: true });
  for (const name of ['alba.aab', 'mapping.txt', 'release.json', 'SHA256SUMS']) gh('release', 'download', env.APP_RELEASE_TAG, '--repo', 'thisislvca/alba', '--dir', out, '--pattern', name);
  const m = validateManifest(json(join(out, 'release.json')));
  if (m.tag !== env.APP_RELEASE_TAG || git('rev-list', '-n', '1', m.tag) !== m.commit) throw new Error('Release tag/source mismatch.');
  verifyFiles(m);
  verifyProvenance(m);
  verifyBundle(approvedCertificate(m.uploadCertificate));
  writeNotes(m);
}
function checkCandidate() {
  const m = validateManifest(json(join(out, 'release.json')));
  checkSource(m);
  verifyFiles(m);
  checkFiles();
  verifyProvenance(m);
  verifyBundle(approvedCertificate(m.uploadCertificate));
  writeNotes(m);
}
function writeNotes(m) {
  const notes = validateNotes(env.APP_NOTES || m.notes);
  if (!notes) throw new Error('Provide release notes before uploading to Play.');
  mkdirSync(join(out, 'metadata/en-US/changelogs'), { recursive: true });
  writeFileSync(join(out, `metadata/en-US/changelogs/${m.versionCode}.txt`), notes);
}

const commands = { prepare, sign: signBundle, package: packageBuild, publish: publishCandidate, verify: verifyCandidate, check: checkCandidate };
if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try { const command = commands[process.argv[2]]; if (!command) throw new Error('Unknown release command.'); await command(); }
  catch (error) { console.error(error.message); process.exitCode = 1; }
}
