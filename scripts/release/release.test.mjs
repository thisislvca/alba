import assert from 'node:assert/strict';
import { test } from 'node:test';
import { draftNotes, releaseChannel, releaseTitle, releaseVersion, validateManifest, validateNotes } from './release.mjs';

test('build IDs increase independently of the chosen marketing version', () => {
  assert.deepEqual(releaseVersion('0.2.0', '1'), { version: '0.2.0', versionCode: 1001, tag: 'app/v0.2.0-build.1001' });
  assert.equal(releaseVersion('0.2.0', '2').versionCode, 1002);
  assert.equal(releaseVersion('0.3.0', '3').versionCode, 1003);
});
test('untrusted version input and exhausted counters cannot reach release commands', () => {
  for (const value of ['0.2', 'v0.2.0', '0.2.0; echo bad', '0.2.0\n', '01.2.0', '../main']) assert.throws(() => releaseVersion(value, '1'));
  for (const value of ['0', '-1', '2.1', 'Infinity']) assert.throws(() => releaseVersion('0.2.0', value));
  assert.throws(() => releaseVersion('0.2.0', '2100000000'));
});
test('Play notes enforce the limit and preserve literal text', () => {
  assert.equal(validateNotes('  Better shared albums.  '), 'Better shared albums.');
  assert.throws(() => validateNotes('x'.repeat(501)));
  assert.equal(validateNotes('😀'.repeat(500)).length, 1000);
});
test('draft notes include app-facing changes without build and website noise', () => {
  const notes = draftNotes([{ sha: 'abc123456', subject: 'feat: shared albums' }, { sha: 'def123456', subject: 'fix(sync): resume interrupted uploads' }, { sha: 'aaa', subject: 'ci: change workflow' }, { sha: 'bbb', subject: 'feat(web): website redesign' }]);
  assert.match(notes, /shared albums/); assert.match(notes, /resume interrupted uploads/); assert.doesNotMatch(notes, /change workflow|website redesign/);
});
test('production requires a signed bundle manifest tied to source and its certificate', () => {
  const m = { schema: 2, packageName: 'me.lvca.alba', ...releaseVersion('0.2.0', '1'), commit: 'a'.repeat(40), signed: true, uploadCertificate: 'b'.repeat(64), files: { 'alba.aab': 'c'.repeat(64) } };
  assert.equal(validateManifest(m), m);
  for (const patch of [{ signed: false }, { commit: 'main' }, { packageName: 'com.example.app' }, { packageName: 'com.mannaworks.mela' }, { tag: 'app/v0.2.1-build.1001' }, { files: {} }]) assert.throws(() => validateManifest({ ...m, ...patch }));
});

test('retired certificates and unexpected asset names fail closed', async () => {
  const { approvedCertificate, retiredCertificate } = await import('./release.mjs');
  for (const value of ['', 'garbage', retiredCertificate, retiredCertificate.toUpperCase().match(/../g).join(':')]) assert.throws(() => approvedCertificate(value));
  assert.equal(approvedCertificate('AA:'.repeat(31) + 'AA'), 'a'.repeat(64));
  const m = { schema: 2, packageName: 'me.lvca.alba', ...releaseVersion('0.2.0', '1'), commit: 'a'.repeat(40), signed: true, uploadCertificate: 'b'.repeat(64), files: { 'alba.aab': 'c'.repeat(64) } };
  for (const name of ['../upload.keystore', 'alba.apk', 'signing.properties']) assert.throws(() => validateManifest({ ...m, files: { ...m.files, [name]: 'c'.repeat(64) } }));
});

test('isolated signing verifies the key, signs the bundle and cleans credentials on success and failure', async () => {
  const { execFileSync, spawnSync } = await import('node:child_process');
  const { mkdtempSync, mkdirSync, writeFileSync, readFileSync, existsSync, rmSync } = await import('node:fs');
  const { tmpdir } = await import('node:os');
  const { join, resolve } = await import('node:path');
  const { createHash, randomBytes } = await import('node:crypto');
  const root = mkdtempSync(join(tmpdir(), 'alba-signing-test-'));
  const script = resolve('scripts/release/release.mjs');
  const password = randomBytes(24).toString('base64');
  const run = (exe, args, options = {}) => execFileSync(exe, args, { cwd: root, stdio: 'pipe', ...options });
  try {
    run('git', ['init', '-q']);
    run('git', ['-c', 'user.name=Test', '-c', 'user.email=test@example.invalid', 'commit', '--allow-empty', '-qm', 'test: fixture']);
    const commit = run('git', ['rev-parse', 'HEAD'], { encoding: 'utf8' }).trim();
    const keyEnv = { ...process.env, ALBA_KEYSTORE_PASSWORD: password };
    const keystore = join(root, 'test.p12');
    run('keytool', ['-genkeypair', '-alias', 'upload', '-keyalg', 'RSA', '-keysize', '2048', '-validity', '1', '-dname', 'CN=Disposable test', '-storetype', 'PKCS12', '-keystore', keystore, '-storepass:env', 'ALBA_KEYSTORE_PASSWORD', '-noprompt'], { env: keyEnv });
    const fingerprint = createHash('sha256').update(run('keytool', ['-exportcert', '-keystore', keystore, '-alias', 'upload', '-storepass:env', 'ALBA_KEYSTORE_PASSWORD'], { env: keyEnv })).digest('hex');
    const out = join(root, '.artifacts/release');
    mkdirSync(out, { recursive: true });
    writeFileSync(join(root, 'payload.txt'), 'unsigned application fixture');
    run('jar', ['--create', '--file', join(out, 'alba.aab'), '-C', root, 'payload.txt']);
    const unsigned = readFileSync(join(out, 'alba.aab'));
    const manifest = { schema: 2, packageName: 'me.lvca.alba', ...releaseVersion('0.2.0', '1'), commit, signed: false, files: { 'alba.aab': createHash('sha256').update(unsigned).digest('hex') } };
    const reset = () => { writeFileSync(join(out, 'alba.aab'), unsigned); writeFileSync(join(out, 'release.json'), JSON.stringify(manifest)); };
    const env = { ...keyEnv, RUNNER_TEMP: root, GITHUB_REPOSITORY: 'thisislvca/alba', GITHUB_REF: 'refs/heads/main', GITHUB_SHA: commit, ALBA_UPLOAD_KEYSTORE_BASE64: readFileSync(keystore).toString('base64'), ALBA_KEY_ALIAS: 'upload', ALBA_UPLOAD_CERT_SHA256: fingerprint };
    reset();
    const success = spawnSync(process.execPath, [script, 'sign'], { cwd: root, env, encoding: 'utf8' });
    assert.equal(success.status, 0, success.stderr);
    assert.equal(JSON.parse(readFileSync(join(out, 'release.json'))).uploadCertificate, fingerprint);
    assert.match(run('jarsigner', ['-verify', join(out, 'alba.aab')], { encoding: 'utf8' }), /jar verified/);
    assert.equal(existsSync(join(root, 'alba-release-credentials')), false);
    for (const patch of [{ ALBA_UPLOAD_CERT_SHA256: 'f'.repeat(64) }, { ALBA_KEYSTORE_PASSWORD: 'incorrect-secret-value' }]) {
      reset();
      const failed = spawnSync(process.execPath, [script, 'sign'], { cwd: root, env: { ...env, ...patch }, encoding: 'utf8' });
      assert.notEqual(failed.status, 0);
      assert.equal(existsSync(join(root, 'alba-release-credentials')), false);
      assert.ok(!failed.stderr.includes(password) && !failed.stderr.includes('incorrect-secret-value'));
      assert.deepEqual(readFileSync(join(out, 'alba.aab')), unsigned);
    }
    reset();
    writeFileSync(join(out, 'upload.keystore'), 'accidental credential file');
    const leakedFile = spawnSync(process.execPath, [script, 'sign'], { cwd: root, env, encoding: 'utf8' });
    assert.notEqual(leakedFile.status, 0);
    assert.match(leakedFile.stderr, /Unexpected file/);
    assert.equal(existsSync(join(root, 'alba-release-credentials')), false);
  } finally { rmSync(root, { recursive: true, force: true }); }
});

 test('alpha is metadata while Android version and build counter remain independent', () => {
  assert.equal(releaseChannel(), 'alpha');
  for (const channel of ['stable', 'beta', '../main', 'alpha\n']) assert.throws(() => releaseChannel(channel));
  const m = { schema: 2, packageName: 'me.lvca.alba', ...releaseVersion('0.1.1', '5'), channel: 'alpha', commit: 'a'.repeat(40), signed: true, uploadCertificate: 'b'.repeat(64), files: { 'alba.aab': 'c'.repeat(64) } };
  assert.equal(validateManifest(m), m);
  assert.equal(m.tag, 'app/v0.1.1-build.1005');
  assert.equal(releaseTitle(m), 'Alba 0.1.1 Alpha (build 1005)');
  assert.equal(releaseTitle(m, true), 'Alba 0.1.1 (build 1005)');
  assert.equal(releaseTitle({ ...m, channel: undefined }), 'Alba 0.1.1 (build 1005)');
  assert.throws(() => validateManifest({ ...m, channel: 'stable' }));
});
