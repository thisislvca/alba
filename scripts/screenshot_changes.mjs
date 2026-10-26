#!/usr/bin/env node
// Helpers for the screenshot golden flow in CI (.github/workflows/ci.yml, AGENTS.md "Screenshot goldens").
//
//   screenshot_changes.mjs classify
//     Run from the repository root after the JVM tests ran in Roborazzi verify mode. Decides whether the golden images
//     alone need updating. Reads the JUnit XML of every module's testDebugUnitTest and Roborazzi's
//     results-summary.json; environment: BUILD_OUTCOME, TESTS_OUTCOME, LINT_OUTCOME (step outcomes) and MODE (verify or
//     record). Appends `update-goldens=true|false` to $GITHUB_OUTPUT and prints the reason.
//   screenshot_changes.mjs trailer      < `git diff --name-status --no-renames` of the goldens
//   screenshot_changes.mjs comment      < same; environment: COMMIT_URL, FILES_URL
import { appendFileSync, existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import { parse } from './device_test_summary.mjs';

export const GOLDENS = 'app/src/test/screenshots';
export const MARKER = '<!-- alba-golden-update -->';
const SCREENSHOT_PACKAGE = 'me.lvca.alba.app.screenshots.';
const SUMMARY = 'app/build/test-results/roborazzi/debug/results-summary.json';

/** A failed test whose only problem is that its image differs from, or has no, golden (Roborazzi's assertion). */
export function isScreenshotFailure(testCase) {
  return testCase.outcome === 'failed' && testCase.className.startsWith(SCREENSHOT_PACKAGE)
    && /^(java\.lang\.AssertionError: )?Roborazzi: /.test(testCase.message);
}

function moduleOfReport(path) {
  const parts = path.split('/');
  const build = parts.lastIndexOf('build');
  return build > 0 ? parts[build - 1] : path;
}

/** A golden's path relative to [GOLDENS], from the absolute path Roborazzi reports. */
function goldenKey(path) {
  const marker = `${GOLDENS}/`;
  const at = path.lastIndexOf(marker);
  return at < 0 ? path : path.slice(at + marker.length);
}

/**
 * Whether only golden images need updating.
 * - `reports`: the `{path, xml}` JUnit reports of every module; `modules`: the modules that have JVM tests.
 * - `summary`: Roborazzi's parsed results-summary.json, or null; `goldens`: the golden files that exist, relative to
 *   [GOLDENS].
 * - `outcomes`: the `build`, `tests` and `lint` step outcomes.
 * Returns `{update, reason}`. Anything unexpected is `update: false`, so the job fails exactly as it does without
 * this flow.
 */
export function classify({ reports, modules, summary, goldens, outcomes, mode }) {
  const no = reason => ({ update: false, reason });
  if (mode !== 'verify') return no('Not in verify mode.');
  if (outcomes.build !== 'success') return no('The build failed.');
  if (outcomes.lint !== 'success') return no('Lint, detekt or ktlint did not pass.');
  const reported = new Set(reports.map(it => moduleOfReport(it.path)));
  const silent = modules.filter(it => !reported.has(it));
  if (silent.length > 0) return no(`No JVM test results from ${silent.join(', ')}.`);
  const cases = reports.flatMap(it => parse(it.xml));
  const failed = cases.filter(it => it.outcome === 'failed');
  const screenshotCases = cases.filter(it => it.className.startsWith(SCREENSHOT_PACKAGE) && it.outcome !== 'skipped');
  if (!summary || !Array.isArray(summary.results)) return no('Roborazzi wrote no results summary.');
  const results = summary.results;
  // Every capture reports exactly one result; fewer means a screenshot test died before it captured.
  if (results.length !== screenshotCases.length) {
    return no(`${screenshotCases.length} screenshot tests ran but Roborazzi reported ${results.length} results.`);
  }
  if (outcomes.tests === 'success') {
    if (failed.length > 0) return no('Tests failed although the step passed.');
    const known = new Set(results.map(it => goldenKey(it.golden_file_path)));
    const orphans = goldens.filter(it => !known.has(it));
    return orphans.length > 0
      ? { update: true, reason: `${orphans.length} golden image(s) no longer belong to any screenshot test.` }
      : no('The goldens match.');
  }
  if (failed.length === 0) return no('The JVM test step failed without a failed test (compile error, crash or timeout).');
  const other = failed.filter(it => !isScreenshotFailure(it));
  if (other.length > 0) return no(`${other.length} test(s) failed that are not screenshot differences.`);
  const different = results.filter(it => it.type === 'changed' || it.type === 'added');
  if (failed.length !== different.length) {
    return no(`${failed.length} screenshot tests failed but Roborazzi reported ${different.length} differences.`);
  }
  return { update: true, reason: `${failed.length} screenshot(s) differ from their goldens and nothing else failed.` };
}

/** `[{status: 'M' | 'A' | 'D', variant, name}]` from `git diff --name-status --no-renames` of the goldens. */
export function parseNameStatus(text) {
  return text.split('\n').filter(Boolean).map(line => {
    const [status, path] = line.split('\t');
    const [variant, ...rest] = path.slice(`${GOLDENS}/`.length).split('/');
    return { status, variant, name: rest.join('/') };
  });
}

const LABELS = { M: 'Changed', A: 'New', D: 'Removed' };

/** Git trailer lines naming each changed golden, for the commit message. */
export function trailer(changes) {
  return changes.map(it => `Golden-${LABELS[it.status]}: ${it.variant}/${it.name}`).join('\n');
}

/** The sticky PR comment: changed, new and removed screens by variant. */
export function comment(changes, { commitUrl, filesUrl }) {
  const commit = commitUrl ? `[${commitUrl.split('/').pop().slice(0, 7)}](${commitUrl})` : 'a commit';
  const lines = [MARKER, '### Screenshot goldens updated', '',
    `CI recorded the goldens on Linux and pushed ${commit} to this branch. The checks run again on it.`, ''];
  for (const variant of [...new Set(changes.map(it => it.variant))].sort()) {
    lines.push(`**${variant}**`, '');
    for (const status of ['M', 'A', 'D']) {
      const names = changes.filter(it => it.variant === variant && it.status === status)
        .map(it => `\`${it.name.replace(/\.png$/, '')}\``);
      if (names.length > 0) lines.push(`- ${LABELS[status]}: ${names.join(', ')}`);
    }
    lines.push('');
  }
  lines.push(`Review the images in [Files changed](${filesUrl}) before merging: merging accepts them.`);
  return `${lines.join('\n')}\n`;
}

function walk(dir, found = []) {
  for (const entry of readdirSync(dir)) {
    const path = join(dir, entry);
    if (statSync(path).isDirectory()) walk(path, found); else found.push(path);
  }
  return found;
}

function runClassify() {
  const modules = readdirSync('.').filter(it => existsSync(join(it, 'src/test')));
  const reports = modules.flatMap(module => {
    const dir = join(module, 'build/test-results/testDebugUnitTest');
    return existsSync(dir)
      ? readdirSync(dir).filter(it => /^TEST-.*\.xml$/.test(it)).map(it => join(dir, it)) : [];
  }).map(path => ({ path, xml: readFileSync(path, 'utf8') }));
  const summary = existsSync(SUMMARY) ? JSON.parse(readFileSync(SUMMARY, 'utf8')) : null;
  const goldens = existsSync(GOLDENS)
    ? walk(GOLDENS).filter(it => it.endsWith('.png')).map(it => relative(GOLDENS, it)) : [];
  const { BUILD_OUTCOME: build, TESTS_OUTCOME: tests, LINT_OUTCOME: lint, MODE: mode } = process.env;
  const result = classify({ reports, modules, summary, goldens, outcomes: { build, tests, lint }, mode });
  if (process.env.GITHUB_OUTPUT) appendFileSync(process.env.GITHUB_OUTPUT, `update-goldens=${result.update}\n`);
  const verdict = result.update ? 'Only the screenshot goldens need updating' : 'Not a golden-only change';
  process.stdout.write(`${verdict}: ${result.reason}\n`);
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const [command] = process.argv.slice(2);
  if (command === 'classify') {
    runClassify();
  } else if (command === 'trailer' || command === 'comment') {
    const changes = parseNameStatus(readFileSync(0, 'utf8'));
    process.stdout.write(command === 'trailer' ? `${trailer(changes)}\n`
      : comment(changes, { commitUrl: process.env.COMMIT_URL, filesUrl: process.env.FILES_URL }));
  } else {
    console.error('Usage: screenshot_changes.mjs classify | trailer | comment');
    process.exit(2);
  }
}
