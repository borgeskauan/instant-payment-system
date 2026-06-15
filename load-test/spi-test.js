import http from 'k6/http';
import grpc from 'k6/net/grpc';
import exec from 'k6/execution';
import { check } from 'k6';
import { Trend, Counter, Gauge } from 'k6/metrics';

const BASE_URL = 'http://localhost:8001';
const GATEWAY_ADDR = 'localhost:9090';
const MSG_TIMEOUT_MS = 10000;
const GRACEFUL_STOP = '60s';
const ACTIVE_DURATION_SECONDS = 60;

const loadProfile = JSON.parse(open('./load-profile.json'));
const TARGET_TX_RATE = loadProfile.targetTxRate;
const HOT_PSP_COUNT = loadProfile.hotPspCount;
const COLD_PSP_COUNT = loadProfile.coldPspCount;
const HOT_TRAFFIC_SHARE = loadProfile.hotTrafficShare;
const MAX_TX_RATE_PER_HOT_SESSION = loadProfile.maxTxRatePerHotSession;
const MAX_TX_RATE_PER_COLD_SESSION = loadProfile.maxTxRatePerColdSession;
const MAX_IN_FLIGHT_PER_SESSION = loadProfile.maxInFlightPerSession;
const LATE_DRAIN_MS = loadProfile.lateDrainMs;
const HOT_TX_RATE = TARGET_TX_RATE * HOT_TRAFFIC_SHARE;
const COLD_TX_RATE = TARGET_TX_RATE * (1 - HOT_TRAFFIC_SHARE);
const HOT_SESSION_COUNT = Math.max(HOT_PSP_COUNT, Math.ceil(HOT_TX_RATE / MAX_TX_RATE_PER_HOT_SESSION));
const COLD_SESSION_COUNT = Math.max(COLD_PSP_COUNT, Math.ceil(COLD_TX_RATE / MAX_TX_RATE_PER_COLD_SESSION));
const HOT_TX_EVERY_MS = 1000 / (HOT_TX_RATE / HOT_SESSION_COUNT);
const COLD_TX_EVERY_MS = 1000 / (COLD_TX_RATE / COLD_SESSION_COUNT);

const client = new grpc.Client();
client.load(['.'], 'notification.proto');

const transferRequestTemplate = JSON.parse(open('./transfer-request-template.json'));
const statusAcceptanceTemplate = JSON.parse(open('./status-acceptance-template.json'));

export const options = {
  scenarios: {
    hot_psps: {
      executor: 'constant-vus',
      vus: HOT_SESSION_COUNT,
      duration: `${ACTIVE_DURATION_SECONDS}s`,
      gracefulStop: GRACEFUL_STOP,
      exec: 'hotPspSession',
    },
    cold_psps: {
      executor: 'constant-vus',
      vus: COLD_SESSION_COUNT,
      duration: `${ACTIVE_DURATION_SECONDS}s`,
      gracefulStop: GRACEFUL_STOP,
      exec: 'coldPspSession',
    },
  },
  thresholds: {
    total_transaction_duration: ['p(99)<4600'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

const transferRequestDuration = new Trend('transfer_request_duration', true);
const fetchMessagesDuration = new Trend('fetch_messages_duration', true);
const statusReportDuration = new Trend('status_report_duration', true);
const confirmationCheckDuration = new Trend('confirmation_check_duration', true);
const totalTransactionDuration = new Trend('total_transaction_duration', true);

const transactionSuccess = new Counter('transaction_success');
const transactionFailure = new Counter('transaction_failure');
const transactionsStarted = new Counter('transactions_started');
const notificationTimeouts = new Counter('notification_timeouts');
const confirmationTimeouts = new Counter('confirmation_timeouts');
const streamMessagesUnmatched = new Counter('stream_messages_unmatched');
const latePacs008Notifications = new Counter('late_pacs008_notifications');
const latePacs002Confirmations = new Counter('late_pacs002_confirmations');
const lateTransactions = new Counter('late_transactions');
const backpressureSkips = new Counter('backpressure_skips');
const hotTransactionsStarted = new Counter('hot_transactions_started');
const coldTransactionsStarted = new Counter('cold_transactions_started');
const hotStartInterval = new Trend('hot_start_interval', true);
const coldStartInterval = new Trend('cold_start_interval', true);
const hotStartDelay = new Trend('hot_start_delay', true);
const coldStartDelay = new Trend('cold_start_delay', true);
const hotPacingSkips = new Counter('hot_pacing_skips');
const coldPacingSkips = new Counter('cold_pacing_skips');
const inFlightGauge = new Gauge('transactions_in_flight');

function delay(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function getPspPair(pspNumber) {
  const suffix = String(pspNumber).padStart(6, '0');

  return {
    pagador: `10${suffix}`,
    recebedor: `20${suffix}`,
  };
}

function positiveModulo(value, divisor) {
  return ((value % divisor) + divisor) % divisor;
}

function getHotPspNumber() {
  return positiveModulo(exec.vu.idInTest - 1, HOT_PSP_COUNT) + 1;
}

function getColdPspNumber() {
  const coldIndex = positiveModulo(exec.vu.idInTest - HOT_PSP_COUNT - 1, COLD_PSP_COUNT);
  return HOT_PSP_COUNT + coldIndex + 1;
}

function generatePacs008(id, ispbPagador, ispbRecebedor) {
  const payload = JSON.parse(JSON.stringify(transferRequestTemplate));
  const timestamp = Date.now();

  payload.GrpHdr.MsgId = `MSG${timestamp}_${__VU}`;
  payload.GrpHdr.CreDtTm = new Date().toISOString();

  if (payload.CdtTrfTxInf && payload.CdtTrfTxInf.length > 0) {
    payload.CdtTrfTxInf[0].PmtId.EndToEndId = id;
    payload.CdtTrfTxInf[0].IntrBkSttlmAmt.value = (Math.random() * 99999 + 100).toFixed(2);
    payload.CdtTrfTxInf[0].DbtrAgt.FinInstnId.ClrSysMmbId.MmbId = ispbPagador;
    payload.CdtTrfTxInf[0].CdtrAgt.FinInstnId.ClrSysMmbId.MmbId = ispbRecebedor;
  }

  return payload;
}

function generatePacs002(originalEndToEndId) {
  const payload = JSON.parse(JSON.stringify(statusAcceptanceTemplate));
  const timestamp = Date.now();

  payload.GrpHdr.MsgId = `STATUS-MSG-${timestamp}_${__VU}`;
  payload.GrpHdr.CreDtTm = new Date().toISOString();

  if (payload.TxInfAndSts && payload.TxInfAndSts.length > 0) {
    payload.TxInfAndSts[0].OrgnlEndToEndId = originalEndToEndId;
  }

  return payload;
}

function extractTxId(msg) {
  if (!msg) return null;

  // direct fields, if they ever appear
  const direct =
    msg.endToEndId ||
    msg.EndToEndId ||
    msg.transactionId ||
    msg.TransactionId;

  if (direct) return direct;

  // payload is a JSON string in your actual gateway message
  if (typeof msg.payload === 'string') {
    try {
      const parsed = JSON.parse(msg.payload);

      return (
        parsed?.CdtTrfTxInf?.[0]?.PmtId?.EndToEndId ||
        parsed?.TxInfAndSts?.[0]?.OrgnlEndToEndId ||
        parsed?.cdtTrfTxInf?.[0]?.pmtId?.endToEndId ||
        parsed?.txInfAndSts?.[0]?.orgnlEndToEndId ||
        null
      );
    } catch (err) {
      console.error(`VU ${__VU}: failed to parse payload JSON: ${String(err)}`);
      return null;
    }
  }

  // fallback in case payload later becomes an object
  if (typeof msg.payload === 'object' && msg.payload !== null) {
    return (
      msg.payload?.CdtTrfTxInf?.[0]?.PmtId?.EndToEndId ||
      msg.payload?.TxInfAndSts?.[0]?.OrgnlEndToEndId ||
      msg.payload?.cdtTrfTxInf?.[0]?.pmtId?.endToEndId ||
      msg.payload?.txInfAndSts?.[0]?.orgnlEndToEndId ||
      null
    );
  }

  return null;
}

function openNotificationStream(ispb, inbox, timedOut, lateCounter, streamName) {
  const stream = new grpc.Stream(
    client,
    'notification.NotificationGateway/StreamNotifications'
  );

  stream.on('data', (msg) => {
    const txId = extractTxId(msg);

    if (!txId) {
      streamMessagesUnmatched.add(1);
      console.warn(`VU ${__VU}: ${streamName} stream received message without txId: ${JSON.stringify(msg)}`);
      return;
    }

    if (timedOut[txId]) {
      lateCounter.add(1);
      lateTransactions.add(1);
      delete timedOut[txId];
      return;
    }

    inbox[txId] = {
      msg,
      receivedAt: Date.now(),
      streamName,
    };
  });

  stream.on('error', (err) => {
    const text = JSON.stringify(err);
    if (text.includes('canceled by client')) return;
    console.error(`VU ${__VU}: ${streamName} stream error for ISPB ${ispb}: ${text}`);
  });

  stream.write({ ispb });
  return stream;
}

function waitForMatch(inbox, txId, timeoutMs, onTimeout) {
  const deadline = Date.now() + timeoutMs;

  return new Promise((resolve) => {
    function poll() {
      if (inbox[txId]) {
        const found = inbox[txId];
        delete inbox[txId];
        resolve(found);
        return;
      }

      if (Date.now() >= deadline) {
        onTimeout?.();
        resolve(null);
        return;
      }

      setTimeout(poll, 10);
    }

    poll();
  });
}

async function runTransactionFlow(state, txId) {
  await delay(0);

  const {
    ispbPagador,
    ispbRecebedor,
    recebedorInbox,
    pagadorInbox,
    recebedorTimedOut,
    pagadorTimedOut,
  } = state;

  const transactionStartTime = Date.now();
  let operationStartTime;

  const pacs008 = generatePacs008(txId, ispbPagador, ispbRecebedor);

  operationStartTime = Date.now();
  const transferRes = http.post(
    `${BASE_URL}/${ispbPagador}/transfer`,
    JSON.stringify(pacs008),
    {
      headers: { 'Content-Type': 'application/octet-stream' },
      tags: { name: 'transfer-request' },
    }
  );
  transferRequestDuration.add(Date.now() - operationStartTime);

  const transferOk = check(transferRes, {
    'transfer request accepted by SPI': (r) => r.status === 200,
  });

  if (!transferOk) {
    transactionFailure.add(1);
    return;
  }

  operationStartTime = Date.now();
  const incomingPayment = await waitForMatch(
    recebedorInbox,
    txId,
    MSG_TIMEOUT_MS,
    () => {
      recebedorTimedOut[txId] = Date.now();
    }
  );
  fetchMessagesDuration.add(Date.now() - operationStartTime);

  const receivedOk = check(incomingPayment, {
    'pacs.008 notification received by recebedor': (msg) => msg !== null,
  });

  if (!receivedOk) {
    notificationTimeouts.add(1);
    transactionFailure.add(1);
    return;
  }

  const pacs002 = generatePacs002(txId);

  operationStartTime = Date.now();
  const ackRes = http.post(
    `${BASE_URL}/${ispbRecebedor}/transfer/status`,
    JSON.stringify(pacs002),
    {
      headers: { 'Content-Type': 'application/octet-stream' },
      tags: { name: 'status-report' },
    }
  );
  statusReportDuration.add(Date.now() - operationStartTime);

  const ackOk = check(ackRes, {
    'status report accepted by SPI': (r) => r.status === 200,
  });

  if (!ackOk) {
    transactionFailure.add(1);
    return;
  }

  operationStartTime = Date.now();
  const confirmation = await waitForMatch(
    pagadorInbox,
    txId,
    MSG_TIMEOUT_MS,
    () => {
      pagadorTimedOut[txId] = Date.now();
    }
  );
  confirmationCheckDuration.add(Date.now() - operationStartTime);

  const confirmedOk = check(confirmation, {
    'pacs.002 confirmation received by pagador': (msg) => msg !== null,
  });

  totalTransactionDuration.add(Date.now() - transactionStartTime);

  if (confirmedOk) {
    transactionSuccess.add(1);
  } else {
    confirmationTimeouts.add(1);
    transactionFailure.add(1);
  }
}

async function runPspSession(pspNumber, newTxEveryMs, metrics) {
  const { pagador: ispbPagador, recebedor: ispbRecebedor } = getPspPair(pspNumber);

  client.connect(GATEWAY_ADDR, { plaintext: true });

  const recebedorInbox = Object.create(null);
  const pagadorInbox = Object.create(null);
  const recebedorTimedOut = Object.create(null);
  const pagadorTimedOut = Object.create(null);

  const recebedorStream = openNotificationStream(
    ispbRecebedor,
    recebedorInbox,
    recebedorTimedOut,
    latePacs008Notifications,
    'recebedor'
  );
  const pagadorStream = openNotificationStream(
    ispbPagador,
    pagadorInbox,
    pagadorTimedOut,
    latePacs002Confirmations,
    'pagador'
  );

  const state = {
    ispbPagador,
    ispbRecebedor,
    recebedorInbox,
    pagadorInbox,
    recebedorTimedOut,
    pagadorTimedOut,
    recebedorStream,
    pagadorStream,
  };
  const inFlight = new Map();
  let txSeq = 0;
  let lastStartAt = null;
  let nextExpectedStartAt = Date.now();

  try {
    while (exec.scenario.progress < 0.98) {
      const waitUntilNextStartMs = nextExpectedStartAt - Date.now();
      if (waitUntilNextStartMs > 0) {
        await delay(waitUntilNextStartMs);
      }

      let scheduledStartAt = nextExpectedStartAt;
      const now = Date.now();
      const startDelayMs = Math.max(0, now - scheduledStartAt);

      if (startDelayMs > newTxEveryMs) {
        const skippedStarts = Math.floor(startDelayMs / newTxEveryMs);
        metrics.pacingSkips.add(skippedStarts);
        scheduledStartAt = now;
        nextExpectedStartAt = now + newTxEveryMs;
      } else {
        nextExpectedStartAt = scheduledStartAt + newTxEveryMs;
      }

      if (inFlight.size >= MAX_IN_FLIGHT_PER_SESSION) {
        backpressureSkips.add(1);
        inFlightGauge.add(inFlight.size);
        continue;
      }

      const txId = `${__VU}-${now}-${txSeq++}`;
      transactionsStarted.add(1);
      metrics.transactionsStarted.add(1);

      if (lastStartAt !== null) {
        metrics.startInterval.add(now - lastStartAt);
      }

      metrics.startDelay.add(startDelayMs);

      lastStartAt = now;

      const promise = runTransactionFlow(state, txId)
        .catch((err) => {
          transactionFailure.add(1);
          console.error(`VU ${__VU}: transaction ${txId} failed with error: ${String(err)}`);
        })
        .finally(() => {
          inFlight.delete(txId);
          delete recebedorInbox[txId];
          delete pagadorInbox[txId];
          inFlightGauge.add(inFlight.size);
        });

      inFlight.set(txId, promise);
      inFlightGauge.add(inFlight.size);
    }

    while (inFlight.size > 0) {
      await Promise.all(Array.from(inFlight.values()));
    }

    await delay(LATE_DRAIN_MS);
    inFlightGauge.add(0);
  } finally {
    try { recebedorStream.end(); } catch (_) {}
    try { pagadorStream.end(); } catch (_) {}
    try { client.close(); } catch (_) {}
  }
}

export async function hotPspSession() {
  await runPspSession(getHotPspNumber(), HOT_TX_EVERY_MS, {
    transactionsStarted: hotTransactionsStarted,
    startInterval: hotStartInterval,
    startDelay: hotStartDelay,
    pacingSkips: hotPacingSkips,
  });
}

export async function coldPspSession() {
  await runPspSession(getColdPspNumber(), COLD_TX_EVERY_MS, {
    transactionsStarted: coldTransactionsStarted,
    startInterval: coldStartInterval,
    startDelay: coldStartDelay,
    pacingSkips: coldPacingSkips,
  });
}

function count(data, metricName) {
  return data.metrics[metricName]?.values?.count || 0;
}

function percentile(data, metricName, percentileName) {
  const value = data.metrics[metricName]?.values?.[percentileName];
  if (value === undefined) return 'n/a';
  return `${(value / 1000).toFixed(2)}s`;
}

function percentileMs(data, metricName, percentileName) {
  const value = data.metrics[metricName]?.values?.[percentileName];
  if (value === undefined) return 'n/a';
  return `${value.toFixed(2)}ms`;
}

export function handleSummary(data) {
  const transactionsStarted = count(data, 'transactions_started');
  const hotStarted = count(data, 'hot_transactions_started');
  const coldStarted = count(data, 'cold_transactions_started');
  const hotPacingSkipCount = count(data, 'hot_pacing_skips');
  const coldPacingSkipCount = count(data, 'cold_pacing_skips');
  const transactionSuccesses = count(data, 'transaction_success');
  const transactionFailures = count(data, 'transaction_failure');
  const notificationTimeoutCount = count(data, 'notification_timeouts');
  const confirmationTimeoutCount = count(data, 'confirmation_timeouts');
  const latePacs008Count = count(data, 'late_pacs008_notifications');
  const latePacs002Count = count(data, 'late_pacs002_confirmations');
  const lateTransactionCount = count(data, 'late_transactions');
  const neverArrivedTransactionCount = transactionFailures - lateTransactionCount;
  const httpRequests = count(data, 'http_reqs');
  const droppedIterations = count(data, 'dropped_iterations');
  const backpressureSkipCount = count(data, 'backpressure_skips');
  const activeTxStartRate = transactionsStarted / ACTIVE_DURATION_SECONDS;
  const hotTxStartRate = hotStarted / ACTIVE_DURATION_SECONDS;
  const coldTxStartRate = coldStarted / ACTIVE_DURATION_SECONDS;
  const activeHttpRequestRate = httpRequests / ACTIVE_DURATION_SECONDS;

  return {
    stdout: `
PIX LOAD SUMMARY
active duration: ${ACTIVE_DURATION_SECONDS}s
target tx start rate: ${TARGET_TX_RATE.toFixed(2)} tx/s
transactions started: ${transactionsStarted}
active tx start rate: ${activeTxStartRate.toFixed(2)} tx/s
hot transactions started: ${hotStarted}
hot tx start rate: ${hotTxStartRate.toFixed(2)} tx/s
cold transactions started: ${coldStarted}
cold tx start rate: ${coldTxStartRate.toFixed(2)} tx/s
hot pacing skips: ${hotPacingSkipCount}
cold pacing skips: ${coldPacingSkipCount}
dropped iterations: ${droppedIterations}
backpressure skips: ${backpressureSkipCount}
HTTP requests: ${httpRequests}
active HTTP request rate: ${activeHttpRequestRate.toFixed(2)} req/s

transactions succeeded: ${transactionSuccesses}
transactions missed SLA: ${transactionFailures}
transactions late: ${lateTransactionCount}
transactions never arrived: ${neverArrivedTransactionCount}
notification timeouts: ${notificationTimeoutCount}
confirmation timeouts: ${confirmationTimeoutCount}
late pacs.008 notifications: ${latePacs008Count}
late pacs.002 confirmations: ${latePacs002Count}

total transaction duration p50: ${percentile(data, 'total_transaction_duration', 'med')}
total transaction duration p95: ${percentile(data, 'total_transaction_duration', 'p(95)')}
total transaction duration p99: ${percentile(data, 'total_transaction_duration', 'p(99)')}
hot start interval p50/p95/p99: ${percentileMs(data, 'hot_start_interval', 'med')} / ${percentileMs(data, 'hot_start_interval', 'p(95)')} / ${percentileMs(data, 'hot_start_interval', 'p(99)')}
cold start interval p50/p95/p99: ${percentileMs(data, 'cold_start_interval', 'med')} / ${percentileMs(data, 'cold_start_interval', 'p(95)')} / ${percentileMs(data, 'cold_start_interval', 'p(99)')}
hot start delay p50/p95/p99: ${percentileMs(data, 'hot_start_delay', 'med')} / ${percentileMs(data, 'hot_start_delay', 'p(95)')} / ${percentileMs(data, 'hot_start_delay', 'p(99)')}
cold start delay p50/p95/p99: ${percentileMs(data, 'cold_start_delay', 'med')} / ${percentileMs(data, 'cold_start_delay', 'p(95)')} / ${percentileMs(data, 'cold_start_delay', 'p(99)')}
threshold target: p99 < 4.60s
`,
  };
}
