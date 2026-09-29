import { afterEach, beforeEach, test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { frames, startup, validate } from './validate_android_measurement.mjs';

let directory;
beforeEach(() => { directory = mkdtempSync(join(tmpdir(), 'mela-measurement-')); });
afterEach(() => { rmSync(directory, { recursive: true, force: true }); });
function sample(text, name = 'sample.txt') {
  const path = join(directory, name);
  writeFileSync(path, text);
  return path;
}
const launch = 'Status: ok\nLaunchState: COLD\nTotalTime: 120\n';
const frameSample = 'Total frames rendered: 60\nJanky frames: 1 (1.67%)\n50th percentile: 7ms\n90th percentile: 12ms\n95th percentile: 18ms\n';
const memory = 'TOTAL PSS: 52000 TOTAL RSS: 81000\nBitmap (malloced): 12 3700\n';

function completeMeasurement() {
  sample(launch, 'first-start.txt');
  for (let i = 1; i <= 5; i++) sample(launch, `cold-start-${i}.txt`);
  sample(frameSample, 'gfxinfo.txt');
  sample(memory, 'meminfo.txt');
}

test('rejects timed-out, incomplete, warm and zero-duration launches', () => {
  for (const text of ['Status: timeout\n', 'Status: ok\nLaunchState: COLD\n',
    launch.replace('COLD', 'WARM'), launch.replace('120', '0')]) {
    assert.throws(() => startup(sample(text)));
  }
  assert.equal(startup(sample(launch)), 120);
});

test('rejects empty and inconsistent frame measurements', () => {
  for (const text of [frameSample.replace('rendered: 60', 'rendered: 0'),
    frameSample.replace('Janky frames: 1', 'Janky frames: 61'),
    frameSample.replace('7ms', '20ms')]) {
    assert.throws(() => frames(sample(text)));
  }
  assert.doesNotThrow(() => frames(sample(frameSample.replace('Janky frames: 1', 'Janky frames: 0'))));
});

test('requires all six launches and accepts a complete measurement', () => {
  completeMeasurement();
  rmSync(join(directory, 'cold-start-5.txt'));
  assert.throws(() => validate(directory));
  sample(launch, 'cold-start-5.txt');
  assert.doesNotThrow(() => validate(directory));
});

test('rejects missing or zero memory metrics', () => {
  completeMeasurement();
  for (const text of [memory.replace('52000', '0'), memory.replace('RSS:', 'Other:'),
    memory.replace('3700', '0')]) {
    sample(text, 'meminfo.txt');
    assert.throws(() => validate(directory));
  }
});

test('CLI prints startup time and reports invalid samples or arguments', () => {
  const script = fileURLToPath(new URL('./validate_android_measurement.mjs', import.meta.url));
  const run = (...args) => spawnSync(process.execPath, [script, ...args], { encoding: 'utf8' });
  const result = run('--startup', sample(launch));
  assert.equal(result.status, 0);
  assert.equal(result.stdout, '120\n');
  assert.equal(run('--startup', sample('Status: timeout\n')).status, 1);
  assert.equal(run().status, 2);
  assert.equal(run('--unknown').status, 2);
  completeMeasurement();
  assert.equal(run(directory).status, 0);
});
