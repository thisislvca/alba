import assert from 'node:assert/strict';
import { test } from 'node:test';
import { classify, comment, parseNameStatus, trailer } from './screenshot_changes.mjs';

const pass = (className, name) => `<testcase classname="${className}" name="${name}" time="0"/>`;
const fail = (className, name, message) =>
  `<testcase classname="${className}" name="${name}" time="0"><failure message="${message}" type="java.lang.AssertionError">${message}</failure></testcase>`;
const report = (module, ...cases) => ({
  path: `${module}/build/test-results/testDebugUnitTest/TEST-x.xml`,
  xml: `<testsuite>${cases.join('')}</testsuite>`,
});
const shot = 'me.lvca.alba.app.screenshots.StandardScreenshots';
const result = (name, type) => ({ type, golden_file_path: `/work/app/src/test/screenshots/standard/${name}.png` });
const roborazzi = name => `java.lang.AssertionError: Roborazzi: /work/app/src/test/screenshots/standard/${name}.png is changed.`;

const base = {
  modules: ['app', 'engine'],
  goldens: ['standard/gallery.png', 'standard/viewer.png'],
  outcomes: { build: 'success', tests: 'failure', lint: 'success' },
  mode: 'verify',
};
const engine = report('engine', pass('e.EngineTest', 'ok'));

test('screenshot differences alone are a golden update', () => {
  const verdict = classify({
    ...base,
    reports: [report('app', fail(shot, 'gallery', roborazzi('gallery')), pass(shot, 'viewer'), pass('a.B', 'c')), engine],
    summary: { results: [result('gallery', 'changed'), result('viewer', 'unchanged')] },
  });
  assert.equal(verdict.update, true);
});

test('a new screenshot without a golden is a golden update', () => {
  const message = 'java.lang.AssertionError: Roborazzi: The original file(/x/standard/viewer.png) was not found.';
  const verdict = classify({
    ...base,
    reports: [report('app', pass(shot, 'gallery'), fail(shot, 'viewer', message)), engine],
    summary: { results: [result('gallery', 'unchanged'), result('viewer', 'added')] },
  });
  assert.equal(verdict.update, true);
});

test('another failing test blocks the update', () => {
  const verdict = classify({
    ...base,
    reports: [report('app', fail(shot, 'gallery', roborazzi('gallery')), fail('a.B', 'c', 'boom')), engine],
    summary: { results: [result('gallery', 'changed')] },
  });
  assert.equal(verdict.update, false);
});

test('a non-Roborazzi failure in a screenshot class blocks the update', () => {
  const verdict = classify({
    ...base,
    reports: [report('app', fail(shot, 'gallery', 'java.lang.IllegalStateException: no activity')), engine],
    summary: { results: [result('gallery', 'unchanged')] },
  });
  assert.equal(verdict.update, false);
});

test('a failed engine test blocks the update', () => {
  const verdict = classify({
    ...base,
    reports: [report('app', fail(shot, 'gallery', roborazzi('gallery'))), report('engine', fail('e.E', 'x', 'boom'))],
    summary: { results: [result('gallery', 'changed')] },
  });
  assert.equal(verdict.update, false);
});

test('a module without results (compile error) blocks the update', () => {
  const verdict = classify({
    ...base,
    reports: [report('app', fail(shot, 'gallery', roborazzi('gallery')))],
    summary: { results: [result('gallery', 'changed')] },
  });
  assert.equal(verdict.update, false);
});

test('a build, lint or detekt failure blocks the update', () => {
  const input = {
    ...base,
    reports: [report('app', fail(shot, 'gallery', roborazzi('gallery'))), engine],
    summary: { results: [result('gallery', 'changed')] },
  };
  assert.equal(classify({ ...input, outcomes: { ...base.outcomes, build: 'failure' } }).update, false);
  assert.equal(classify({ ...input, outcomes: { ...base.outcomes, lint: 'failure' } }).update, false);
});

test('a screenshot test that died before capturing blocks the update', () => {
  const verdict = classify({
    ...base,
    reports: [report('app', fail(shot, 'gallery', roborazzi('gallery')), fail(shot, 'viewer', 'java.lang.OutOfMemoryError')), engine],
    summary: { results: [result('gallery', 'changed')] },
  });
  assert.equal(verdict.update, false);
});

test('a failed step without a failed test blocks the update', () => {
  const verdict = classify({
    ...base,
    reports: [report('app', pass(shot, 'gallery')), engine],
    summary: { results: [result('gallery', 'unchanged')] },
  });
  assert.equal(verdict.update, false);
});

test('orphaned goldens are removed when everything passes', () => {
  const input = {
    ...base,
    outcomes: { build: 'success', tests: 'success', lint: 'success' },
    reports: [report('app', pass(shot, 'gallery')), engine],
    summary: { results: [result('gallery', 'unchanged')] },
  };
  assert.equal(classify({ ...input, goldens: ['standard/gallery.png', 'standard/old.png'] }).update, true);
  assert.equal(classify({ ...input, goldens: ['standard/gallery.png'] }).update, false);
});

test('only verify mode updates', () => {
  const verdict = classify({
    ...base,
    mode: 'record',
    reports: [report('app', fail(shot, 'gallery', roborazzi('gallery'))), engine],
    summary: { results: [result('gallery', 'changed')] },
  });
  assert.equal(verdict.update, false);
});

const diff = 'M\tapp/src/test/screenshots/dark/gallery.png\nA\tapp/src/test/screenshots/dark/new.png\nD\tapp/src/test/screenshots/wide/old.png\n';

test('the trailer lists every changed golden', () => {
  assert.equal(trailer(parseNameStatus(diff)),
    'Golden-Changed: dark/gallery.png\nGolden-New: dark/new.png\nGolden-Removed: wide/old.png');
});

test('the comment groups screens by variant and sends the reviewer to Files changed', () => {
  const body = comment(parseNameStatus(diff), { commitUrl: 'https://github.com/o/r/commit/abcdef1234', filesUrl: 'https://x/files' });
  assert.match(body, /^<!-- alba-golden-update -->/);
  assert.match(body, /\*\*dark\*\*\n\n- Changed: `gallery`\n- New: `new`/);
  assert.match(body, /\*\*wide\*\*\n\n- Removed: `old`/);
  assert.match(body, /\[abcdef1\]\(https:\/\/github.com\/o\/r\/commit\/abcdef1234\)/);
  assert.match(body, /\[Files changed\]\(https:\/\/x\/files\) before merging/);
});
