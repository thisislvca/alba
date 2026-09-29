import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';
import { test } from 'node:test';

const policy = fileURLToPath(new URL('./android_emulator_graphics.sh', import.meta.url));
const apple = 'GLES: Google (Apple), Android Emulator OpenGL ES Translator (Apple M4 Pro), OpenGL ES 3.0 (4.1 Metal - 91.7)';
function check({ os = 'Darwin', arch = 'arm64', mode, required, renderer = apple } = {}) {
  const env = { ...process.env };
  delete env.MELA_EMULATOR_GPU;
  delete env.MELA_REQUIRE_HARDWARE_GPU;
  if (mode !== undefined) env.MELA_EMULATOR_GPU = mode;
  if (required !== undefined) env.MELA_REQUIRE_HARDWARE_GPU = required;
  return spawnSync('/bin/bash', ['-c', `
    set -euo pipefail
    source "$1"
    uname() { if [[ "$1" == '-s' ]]; then printf '%s' "$test_os"; else printf '%s' "$test_arch"; fi; }
    test_os="$2"
    test_arch="$3"
    mela_configure_emulator_graphics
    mela_verify_emulator_graphics "$4"
    printf 'mode=%s' "$MELA_GPU_MODE"
  `, 'gpu-policy-test', policy, os, arch, renderer], { env, encoding: 'utf8' });
}

test('macOS defaults to host and accepts verified Apple Metal rendering', () => {
  const result = check();
  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stdout, /mode=host/);
});

test('macOS rejects automatic/software overrides and disabling verification', () => {
  for (const mode of ['auto', 'software', 'swiftshader', 'swangle', 'llvmpipe', 'lavapipe']) {
    assert.equal(check({ mode }).status, 2, mode);
  }
  assert.equal(check({ required: '0' }).status, 2);
});

test('macOS rejects software fallback, unknown renderers, and a non-Apple renderer on Apple silicon', () => {
  for (const renderer of ['', 'GLES: unknown', 'GLES: Intel Iris',
    'GLES: Google, ANGLE SwiftShader Device (LLVM 10.0.0)',
    'GLES: Apple Metal SwiftShader', 'GLES: llvmpipe', 'GLES: lavapipe', 'GLES: software', 'GLES: swangle']) {
    assert.equal(check({ renderer }).status, 1, renderer);
  }
});

test('Intel Macs accept their hardware renderer', () => {
  assert.equal(check({ arch: 'x86_64', renderer: 'GLES: AMD Radeon Pro 5500M, OpenGL ES 3.0' }).status, 0);
});

test('explicit Linux software rendering remains available for CI', () => {
  assert.equal(check({ os: 'Linux', arch: 'x86_64', mode: 'software', required: '0', renderer: 'GLES: SwiftShader' }).status, 0);
  assert.equal(check({ os: 'Linux', arch: 'x86_64', renderer: 'GLES: SwiftShader' }).status, 1);
});
