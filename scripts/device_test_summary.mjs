#!/usr/bin/env node
// Summarize device-test JUnit XML as Markdown for the CI job summary, so each failure is listed on the run page.
// Usage: device_test_summary.mjs <title> <TEST-*.xml>...
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const entities = { amp: '&', lt: '<', gt: '>', quot: '"', apos: "'" };
function unescape(text) {
  return text.replace(/&(#x[0-9a-f]+|#\d+|\w+);/gi, (match, entity) => {
    if (entity[0] !== '#') return entities[entity] ?? match;
    return String.fromCodePoint(entity[1] === 'x' ? parseInt(entity.slice(2), 16) : Number(entity.slice(1)));
  });
}

function attribute(attributes, name) {
  const match = attributes.match(new RegExp(`\\b${name}="([^"]*)"`));
  return match ? unescape(match[1]) : '';
}

/** The test cases in one JUnit XML report, each with its outcome and, for a failure, the exception's first line. */
export function parse(xml) {
  const cases = [];
  for (const [, attributes, body = ''] of xml.matchAll(/<testcase\b([^>]*?)(?:\/>|>([\s\S]*?)<\/testcase>)/g)) {
    const failure = body.match(/<(failure|error)\b([^>]*?)(?:\/>|>([\s\S]*?)<\/\1>)/);
    const detail = failure ? unescape(failure[3] ?? '').trim() || attribute(failure[2], 'message') : '';
    cases.push({
      className: attribute(attributes, 'classname'),
      name: attribute(attributes, 'name'),
      outcome: failure ? 'failed' : /<skipped\b/.test(body) ? 'skipped' : 'passed',
      message: detail.split('\n')[0].trim(),
    });
  }
  return cases;
}

/** The module a report belongs to: the path segment before `build/`. */
export function moduleOf(path) {
  const parts = path.split('/');
  const build = parts.indexOf('build');
  return build > 0 ? parts[build - 1] : path;
}

export function summarize(title, reports) {
  const lines = [`### ${title}`, ''];
  if (reports.length === 0) {
    return [...lines, 'No test results were produced. Check the step logs: the emulator or the build may have failed first.', ''].join('\n');
  }
  const modules = new Map();
  for (const { path, xml } of reports) {
    const module = moduleOf(path);
    modules.set(module, [...(modules.get(module) ?? []), ...parse(xml)]);
  }
  lines.push('| Module | Passed | Failed | Skipped |', '| --- | ---: | ---: | ---: |');
  const failures = [];
  for (const [module, cases] of [...modules].sort(([a], [b]) => a.localeCompare(b))) {
    const count = outcome => cases.filter(it => it.outcome === outcome).length;
    lines.push(`| ${module} | ${count('passed')} | ${count('failed')} | ${count('skipped')} |`);
    for (const it of cases.filter(it => it.outcome === 'failed')) failures.push({ module, ...it });
  }
  if (failures.length > 0) {
    lines.push('', `**${failures.length} failed**`, '');
    for (const it of failures) {
      const test = `${it.className.split('.').pop()}.${it.name}`;
      lines.push(`- \`${it.module}\` \`${test}\`: ${it.message.replaceAll('|', '\\|').slice(0, 300)}`);
    }
  }
  return [...lines, ''].join('\n');
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const [title, ...paths] = process.argv.slice(2);
  process.stdout.write(summarize(title, paths.map(path => ({ path, xml: readFileSync(path, 'utf8') }))));
}
