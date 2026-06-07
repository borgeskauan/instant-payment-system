import { readFile } from 'node:fs/promises';
import test from 'node:test';
import assert from 'node:assert/strict';

const source = await readFile(new URL('./spi-test.js', import.meta.url), 'utf8');

test('models hot and cold PSP sessions with a shared session runner', () => {
  assert.match(source, /const BASE_URL = 'http:\/\/localhost:8001'/);
  assert.match(source, /const GATEWAY_ADDR = 'localhost:9090'/);
  assert.match(source, /const MSG_TIMEOUT_MS = 10000/);
  assert.match(source, /const HOT_VUS = 10/);
  assert.match(source, /const HOT_TX_EVERY_MS = 6/);
  assert.match(source, /const MAX_IN_FLIGHT = 250/);
  assert.match(
    source,
    /const HOT_VUS = 10;\s+const HOT_TX_EVERY_MS = 6;\s+const COLD_VUS = 40;\s+const COLD_TX_EVERY_MS = 100;/
  );
  assert.match(source, /client\.load\(\['\.'\], 'notification\.proto'\)/);
  assert.match(source, /hot_psps:\s*{/);
  assert.match(source, /cold_psps:\s*{/);
  assert.match(source, /exec:\s*'hotPspSession'/);
  assert.match(source, /exec:\s*'coldPspSession'/);
  assert.match(source, /async function runPspSession\s*\(\s*newTxEveryMs\s*\)/);
  assert.match(source, /export async function hotPspSession\s*\(\s*\)/);
  assert.match(source, /export async function coldPspSession\s*\(\s*\)/);
  assert.match(source, /await runPspSession\s*\(\s*HOT_TX_EVERY_MS\s*\)/);
  assert.match(source, /await runPspSession\s*\(\s*COLD_TX_EVERY_MS\s*\)/);
  assert.doesNotMatch(source, /export default/);
  assert.doesNotMatch(source, /__ENV/);
  assert.doesNotMatch(source, /parseInt/);
  assert.doesNotMatch(source, /NEW_TX_EVERY_MS/);
  assert.doesNotMatch(source, /'default'/);
  assert.doesNotMatch(source, /PSP_COUNT/);
  assert.doesNotMatch(source, /hashString/);
  assert.doesNotMatch(source, /generateIspbFromKey/);
});
