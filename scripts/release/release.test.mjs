import assert from 'node:assert/strict';
import { test } from 'node:test';
import { draftNotes, releaseVersion, validateManifest, validateNotes } from './release.mjs';

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
test('production requires a signed manifest tied to source and hashed APK/AAB', () => {
  const m = { schema: 1, packageName: 'com.mannaworks.mela', ...releaseVersion('0.2.0', '1'), commit: 'a'.repeat(40), signed: true, files: { 'alba.apk': 'b'.repeat(64), 'alba.aab': 'c'.repeat(64) } };
  assert.equal(validateManifest(m), m);
  for (const patch of [{ signed: false }, { commit: 'main' }, { packageName: 'com.example.app' }, { tag: 'app/v0.2.1-build.1001' }, { files: {} }]) assert.throws(() => validateManifest({ ...m, ...patch }));
});
