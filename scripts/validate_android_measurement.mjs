#!/usr/bin/env bun
// Reject incomplete Android runtime samples before publishing measurements.
import { readFileSync } from 'node:fs';
import { basename, join } from 'node:path';
import { fileURLToPath } from 'node:url';

function integer(text, pattern, label, minimum = 1) {
  const match = text.match(pattern);
  const value = match ? Number(match[1]) : NaN;
  if (!Number.isSafeInteger(value) || value < minimum) {
    throw new Error(`Missing or invalid ${label}`);
  }
  return value;
}

export function startup(path) {
  const text = readFileSync(path, 'utf8');
  if (!/^Status: ok\s*$/m.test(text)) {
    throw new Error(`${basename(path)}: activity launch did not succeed`);
  }
  if (!/^LaunchState: COLD\s*$/m.test(text)) {
    throw new Error(`${basename(path)}: sample was not a cold launch`);
  }
  return integer(text, /^TotalTime:\s*(\d+)\s*$/m, 'startup time');
}

export function frames(path) {
  const text = readFileSync(path, 'utf8');
  const total = integer(text, /^Total frames rendered:\s*(\d+)\s*$/m, 'rendered frame count');
  const janky = integer(text, /^Janky frames:\s*(\d+)/m, 'janky frame count', 0);
  if (janky > total) throw new Error('Janky frame count exceeds rendered frame count');
  const percentiles = [50, 90, 95].map(p =>
    integer(text, new RegExp(`^${p}th percentile:\\s*(\\d+)ms\\s*$`, 'm'), `p${p} frame time`)
  );
  if (percentiles.some((value, index) => index > 0 && value < percentiles[index - 1])) {
    throw new Error('Frame percentiles are inconsistent');
  }
}

export function validate(directory) {
  for (const name of ['first-start', ...Array.from({ length: 5 }, (_, i) => `cold-start-${i + 1}`)]) {
    startup(join(directory, `${name}.txt`));
  }
  frames(join(directory, 'gfxinfo.txt'));
  const memory = readFileSync(join(directory, 'meminfo.txt'), 'utf8');
  integer(memory, /TOTAL PSS:\s*(\d+)/m, 'PSS');
  integer(memory, /TOTAL RSS:\s*(\d+)/m, 'RSS');
  integer(memory, /Bitmap \(malloced\):\s*\d+\s+(\d+)/m, 'bitmap memory');
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const args = process.argv.slice(2);
  const startupOnly = args.includes('--startup');
  const paths = args.filter(arg => arg !== '--startup');
  if (paths.length !== 1 || paths[0].startsWith('-') || args.length !== (startupOnly ? 2 : 1)) {
    console.error('Usage: bun scripts/validate_android_measurement.mjs [--startup] <path>');
    process.exitCode = 2;
  } else {
    try {
      if (startupOnly) console.log(startup(paths[0]));
      else validate(paths[0]);
    } catch (error) {
      console.error(`Invalid Android measurement: ${error.message}`);
      process.exitCode = 1;
    }
  }
}
