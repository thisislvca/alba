import { execFileSync } from 'node:child_process';
import { appendFileSync, copyFileSync, existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

export const packageName = 'com.mannaworks.mela';
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
export function validateManifest(m) {
  const expected = releaseVersion(m.version, '1', String(m.versionCode - 1));
  if (m.schema !== 1 || m.packageName !== packageName || m.tag !== expected.tag || !/^[a-f0-9]{40}$/.test(m.commit) || !m.signed) throw new Error('Invalid signed release manifest.');
  for (const file of ['alba.apk', 'alba.aab']) if (!/^[a-f0-9]{64}$/.test(m.files?.[file])) throw new Error(`Missing hash for ${file}.`);
  return m;
}
const env = process.env;
const out = resolve('.artifacts/release');
const credentials = join(env.RUNNER_TEMP || '.artifacts', 'alba-release-credentials');
const run = (exe, args, options = {}) => execFileSync(exe, args, { encoding: 'utf8', ...options });
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
    requireEnv(['ALBA_UPLOAD_KEYSTORE_BASE64', 'ALBA_KEYSTORE_PASSWORD', 'ALBA_KEY_ALIAS', 'ALBA_KEY_PASSWORD', 'ALBA_UPLOAD_CERT_SHA256', 'ALBA_TAG_SIGNING_KEY', 'ALBA_TAG_SIGNING_PUBLIC_KEY']);
    if (env.UPLOAD_TO_PLAY === 'true') {
      requireEnv(['GOOGLE_PLAY_SERVICE_ACCOUNT_JSON']);
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
  save(join(out, 'release.json'), { schema: 1, packageName, ...v, commit, runUrl: `https://github.com/thisislvca/alba/actions/runs/${env.GITHUB_RUN_ID}`, signed: false, notes, files: {} });
  writeFileSync(join(out, 'NOTES.md'), `${notes || draftNotes(commits)}\n\nSource: https://github.com/thisislvca/alba/commit/${commit}\nBuild: ${v.versionCode}\n\n${previous.startsWith('app/v') ? `Changes: https://github.com/thisislvca/alba/compare/${previous}...${commit}` : 'First app release candidate.'}\n`);
  output('version_code', v.versionCode); output('tag', v.tag);
}

function setupSigning() {
  mkdirSync(credentials, { recursive: true, mode: 0o700 });
  const keystore = join(credentials, 'upload.keystore');
  writeFileSync(keystore, Buffer.from(env.ALBA_UPLOAD_KEYSTORE_BASE64, 'base64'), { mode: 0o600 });
  const cert = run('keytool', ['-list', '-v', '-keystore', keystore, '-alias', env.ALBA_KEY_ALIAS, '-storepass:env', 'ALBA_KEYSTORE_PASSWORD']);
  const actual = cert.match(/SHA256:\s*([A-F0-9:]+)/i)?.[1]?.replaceAll(':', '').toLowerCase();
  const expected = env.ALBA_UPLOAD_CERT_SHA256.replaceAll(':', '').toLowerCase();
  if (!actual || actual !== expected) throw new Error('Upload certificate does not match the approved fingerprint.');
  const escape = value => value.replaceAll('\\', '\\\\').replaceAll(' ', '\\ ').replaceAll('\t', '\\t').replaceAll('\n', '\\n').replaceAll('\r', '\\r');
  const properties = join(credentials, 'signing.properties');
  writeFileSync(properties, `storeFile=${escape(keystore)}\nstorePassword=${escape(env.ALBA_KEYSTORE_PASSWORD)}\nkeyAlias=${escape(env.ALBA_KEY_ALIAS)}\nkeyPassword=${escape(env.ALBA_KEY_PASSWORD)}\n`, { mode: 0o600 });
  const key = join(credentials, 'tag-key');
  writeFileSync(key, env.ALBA_TAG_SIGNING_KEY + '\n', { mode: 0o600 });
  const publicKey = run('ssh-keygen', ['-y', '-f', key]).trim().split(/\s+/).slice(0, 2).join(' ');
  if (publicKey !== env.ALBA_TAG_SIGNING_PUBLIC_KEY.trim().split(/\s+/).slice(0, 2).join(' ')) throw new Error('Tag signing key does not match its public key.');
  if (env.GITHUB_ENV) appendFileSync(env.GITHUB_ENV, `MELA_SIGNING_PROPERTIES=${properties}\nALBA_REQUIRE_RELEASE_SIGNING=true\n`);
}

function packageBuild() {
  const m = json(join(out, 'release.json'));
  const signed = env.DRY_RUN !== 'true';
  const apk = signed ? 'app/build/outputs/apk/release/app-release.apk' : 'app/build/outputs/apk/release/app-release-unsigned.apk';
  const aab = 'app/build/outputs/bundle/release/app-release.aab';
  if (!existsSync(apk) || !existsSync(aab)) throw new Error('Expected APK/AAB were not built.');
  const tools = join(env.ANDROID_HOME || env.ANDROID_SDK_ROOT, 'build-tools', '37.0.0');
  const badging = run(join(tools, 'aapt'), ['dump', 'badging', apk]);
  const match = badging.match(/package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'/);
  if (!match || match[1] !== m.packageName || Number(match[2]) !== m.versionCode || match[3] !== m.version) throw new Error('APK identity/version does not match the release manifest.');
  if (signed) {
    const verification = run(join(tools, 'apksigner'), ['verify', '--print-certs', apk]);
    const cert = verification.match(/certificate SHA-256 digest:\s*([a-f0-9]+)/i)?.[1]?.toLowerCase();
    if (cert !== env.ALBA_UPLOAD_CERT_SHA256.replaceAll(':', '').toLowerCase()) throw new Error('APK signing certificate mismatch.');
    const verified = run('jarsigner', ['-verify', aab]);
    if (!verified.includes('jar verified.')) throw new Error('AAB signature could not be verified.');
    const bundleCert = run('keytool', ['-printcert', '-jarfile', aab]);
    const bundleHash = bundleCert.match(/SHA256:\s*([A-F0-9:]+)/i)?.[1]?.replaceAll(':', '').toLowerCase();
    if (bundleHash !== cert) throw new Error('AAB signing certificate mismatch.');
  }
  for (const [name, file] of [['alba.apk', apk], ['alba.aab', aab]]) { copyFileSync(file, join(out, name)); m.files[name] = hash(join(out, name)); }
  const mapping = 'app/build/outputs/mapping/release/mapping.txt';
  if (existsSync(mapping)) { copyFileSync(mapping, join(out, 'mapping.txt')); m.files['mapping.txt'] = hash(join(out, 'mapping.txt')); }
  m.signed = signed;
  save(join(out, 'release.json'), m);
  writeFileSync(join(out, 'SHA256SUMS'), Object.entries(m.files).map(([name, digest]) => `${digest}  ${name}`).join('\n') + '\n');
  if (signed) run('ssh-keygen', ['-Y', 'sign', '-f', join(credentials, 'tag-key'), '-n', 'alba-release', join(out, 'release.json')]);
}

function publishCandidate() {
  const m = validateManifest(json(join(out, 'release.json')));
  git('config', 'user.name', 'Alba releases'); git('config', 'user.email', 'thisislvca@users.noreply.github.com');
  git('config', 'gpg.format', 'ssh'); git('config', 'user.signingkey', join(credentials, 'tag-key'));
  git('tag', '-s', m.tag, m.commit, '-m', `Alba ${m.version}, build ${m.versionCode}`);
  git('-c', 'credential.helper=', '-c', 'credential.helper=!gh auth git-credential', 'push', 'https://github.com/thisislvca/alba.git', `refs/tags/${m.tag}`);
  gh('release', 'create', m.tag, '--repo', 'thisislvca/alba', '--verify-tag', '--draft', '--prerelease', '--title', `Alba ${m.version} (build ${m.versionCode})`, '--notes-file', join(out, 'NOTES.md'), ...Object.keys(m.files).map(name => join(out, name)), join(out, 'release.json'), join(out, 'release.json.sig'), join(out, 'SHA256SUMS'));
  console.log(`Draft release created: ${m.tag}`);
}

function verifyCandidate() {
  requireEnv(['ALBA_TAG_SIGNING_PUBLIC_KEY']);
  if (!/^app\/v\d+\.\d+\.\d+-build\.\d+$/.test(env.APP_RELEASE_TAG || '')) throw new Error('Select an app/vX.Y.Z-build.N tag.');
  mkdirSync(out, { recursive: true });
  gh('release', 'download', env.APP_RELEASE_TAG, '--repo', 'thisislvca/alba', '--dir', out);
  const m = validateManifest(json(join(out, 'release.json')));
  if (m.tag !== env.APP_RELEASE_TAG) throw new Error('Manifest belongs to a different release.');
  const allowed = join(out, 'allowed-signers');
  writeFileSync(allowed, `* ${env.ALBA_TAG_SIGNING_PUBLIC_KEY.trim()}\n`);
  run('ssh-keygen', ['-Y', 'verify', '-f', allowed, '-I', 'alba-release', '-n', 'alba-release', '-s', join(out, 'release.json.sig')], { input: readFileSync(join(out, 'release.json')) });
  if (git('rev-list', '-n', '1', m.tag) !== m.commit) throw new Error('Tag does not point to the recorded source commit.');
  git('-c', 'gpg.format=ssh', '-c', `gpg.ssh.allowedSignersFile=${allowed}`, 'verify-tag', m.tag);
  for (const [name, digest] of Object.entries(m.files)) {
    if (!['alba.apk', 'alba.aab', 'mapping.txt'].includes(name) || hash(join(out, name)) !== digest) throw new Error('Release asset hash mismatch.');
  }
  const notes = validateNotes(env.APP_NOTES || m.notes);
  if (!notes) throw new Error('Provide release notes before publishing to production.');
  mkdirSync(join(out, 'metadata/en-US/changelogs'), { recursive: true });
  writeFileSync(join(out, `metadata/en-US/changelogs/${m.versionCode}.txt`), notes);
}

const commands = { prepare, signing: setupSigning, package: packageBuild, publish: publishCandidate, verify: verifyCandidate };
if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try { const command = commands[process.argv[2]]; if (!command) throw new Error('Unknown release command.'); await command(); }
  catch (error) { console.error(error.message); process.exitCode = 1; }
}
