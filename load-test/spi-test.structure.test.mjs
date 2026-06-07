import { readFile } from 'node:fs/promises';
import test from 'node:test';
import assert from 'node:assert/strict';

const source = await readFile(new URL('./spi-test.js', import.meta.url), 'utf8');

test('models hot and cold PSP sessions with a shared session runner', () => {
  assert.match(source, /const BASE_URL = 'http:\/\/localhost:8001'/);
  assert.match(source, /const GATEWAY_ADDR = 'localhost:9090'/);
  assert.match(source, /const MSG_TIMEOUT_MS = 10000/);
  assert.match(source, /const LATE_DRAIN_MS = 30000/);
  assert.match(source, /const GRACEFUL_STOP = '60s'/);
  assert.match(source, /const ACTIVE_DURATION_SECONDS = 60/);
  assert.match(source, /const HOT_VUS = 10/);
  assert.match(source, /const HOT_TX_EVERY_MS = 6/);
  assert.match(source, /const MAX_IN_FLIGHT = 250/);
  assert.match(
    source,
    /const HOT_VUS = 10;\s+const HOT_TX_EVERY_MS = 6;\s+const COLD_VUS = 40;\s+const COLD_TX_EVERY_MS = 100;/
  );
  assert.match(source, /client\.load\(\['\.'\], 'notification\.proto'\)/);
  assert.match(source, /const latePacs008Notifications = new Counter\('late_pacs008_notifications'\)/);
  assert.match(source, /const latePacs002Confirmations = new Counter\('late_pacs002_confirmations'\)/);
  assert.match(source, /const lateTransactions = new Counter\('late_transactions'\)/);
  assert.match(source, /hot_psps:\s*{/);
  assert.match(source, /cold_psps:\s*{/);
  assert.match(source, /exec:\s*'hotPspSession'/);
  assert.match(source, /exec:\s*'coldPspSession'/);
  assert.match(source, /gracefulStop:\s*GRACEFUL_STOP/);
  assert.match(source, /async function runPspSession\s*\(\s*newTxEveryMs\s*\)/);
  assert.match(source, /function waitForMatch\s*\(\s*inbox,\s*txId,\s*timeoutMs,\s*onTimeout\s*\)/);
  assert.match(source, /onTimeout\?\.\(\)/);
  assert.match(source, /await delay\(LATE_DRAIN_MS\)/);
  assert.match(source, /lateTransactions\.add\(1\)/);
  assert.match(source, /recebedorTimedOut\[txId\] = Date\.now\(\)/);
  assert.match(source, /pagadorTimedOut\[txId\] = Date\.now\(\)/);
  assert.match(source, /export async function hotPspSession\s*\(\s*\)/);
  assert.match(source, /export async function coldPspSession\s*\(\s*\)/);
  assert.match(source, /export function handleSummary\s*\(\s*data\s*\)/);
  assert.match(source, /PIX LOAD SUMMARY/);
  assert.match(source, /active tx start rate/);
  assert.match(source, /const neverArrivedTransactionCount = transactionFailures - lateTransactionCount/);
  assert.match(source, /transactions missed SLA:/);
  assert.match(source, /transactions late:/);
  assert.match(source, /transactions never arrived:/);
  assert.match(source, /transactionsStarted \/ ACTIVE_DURATION_SECONDS/);
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
