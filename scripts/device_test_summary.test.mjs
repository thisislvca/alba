import { test } from 'node:test';
import assert from 'node:assert/strict';
import { moduleOf, parse, summarize } from './device_test_summary.mjs';

const report = `<?xml version='1.0' encoding='UTF-8' ?>
<testsuite name="pixel2Api36" tests="4" failures="1" errors="0" skipped="1">
  <testcase name="opens" classname="me.lvca.alba.app.gallery.GalleryTest" time="1.2" />
  <testcase name="closes" classname="me.lvca.alba.app.gallery.GalleryTest" time="3.4">
    <failure>androidx.compose.ui.test.ComposeTimeoutException: Condition still not satisfied after 30000 ms
	at me.lvca.alba.app.gallery.GalleryTest.closes(GalleryTest.kt:12)</failure>
  </testcase>
  <testcase name="skips" classname="me.lvca.alba.app.gallery.GalleryTest" time="0"><skipped /></testcase>
  <testcase name="crashes" classname="me.lvca.alba.app.gallery.OtherTest" time="0">
    <error message="Test failed to run to completion. Reason: &apos;Instrumentation run failed due to Process crashed.&apos;" />
  </testcase>
</testsuite>`;

test('reads outcomes and the first line of each failure', () => {
  assert.deepEqual(parse(report).map(it => [it.name, it.outcome, it.message]), [
    ['opens', 'passed', ''],
    ['closes', 'failed', 'androidx.compose.ui.test.ComposeTimeoutException: Condition still not satisfied after 30000 ms'],
    ['skips', 'skipped', ''],
    ['crashes', 'failed', "Test failed to run to completion. Reason: 'Instrumentation run failed due to Process crashed.'"],
  ]);
});

test('names the module from the report path', () => {
  assert.equal(moduleOf('app/build/outputs/androidTest-results/managedDevice/debug/pixel2Api36/TEST-pixel2Api36.xml'), 'app');
  assert.equal(moduleOf('protocol-icloud-web/build/outputs/x.xml'), 'protocol-icloud-web');
});

test('tabulates modules and lists every failure', () => {
  const summary = summarize('Android 36 device tests', [
    { path: 'engine/build/TEST-a.xml', xml: '<testsuite><testcase name="ok" classname="E" /></testsuite>' },
    { path: 'app/build/TEST-b.xml', xml: report },
  ]);
  assert.match(summary, /\| app \| 1 \| 2 \| 1 \|\n\| engine \| 1 \| 0 \| 0 \|/);
  assert.match(summary, /\*\*2 failed\*\*/);
  assert.match(summary, /- `app` `GalleryTest.closes`: androidx.compose.ui.test.ComposeTimeoutException/);
  assert.match(summary, /- `app` `OtherTest.crashes`: Test failed to run/);
});

test('says so when no results were produced', () => {
  assert.match(summarize('Android 30 device tests', []), /No test results were produced/);
});
