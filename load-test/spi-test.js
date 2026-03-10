import http from 'k6/http';
import grpc from 'k6/net/grpc';
import exec from 'k6/execution';
import { check } from 'k6';
import { Trend, Counter, Gauge } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8001';
const GATEWAY_ADDR = __ENV.GATEWAY_ADDR || 'localhost:9090';
const MSG_TIMEOUT_MS = parseInt(__ENV.MSG_TIMEOUT_MS || '3000', 10);
const NEW_TX_EVERY_MS = parseInt(__ENV.NEW_TX_EVERY_MS || '200', 10);
const MAX_IN_FLIGHT = parseInt(__ENV.MAX_IN_FLIGHT || '20', 10);

const client = new grpc.Client();
client.load([__ENV.PROTO_DIR || '.'], 'notification.proto');

const transferRequestTemplate = JSON.parse(open('./transfer-request-template.json'));
const statusAcceptanceTemplate = JSON.parse(open('./status-acceptance-template.json'));

export const options = {
  scenarios: {
    psp_sessions: {
      executor: 'constant-vus',
      vus: 50,
      duration: '1m',
      gracefulStop: '5s',
    },
  },
  thresholds: {
    total_transaction_duration: ['p(99)<4000'],
  },
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
const inFlightGauge = new Gauge('transactions_in_flight');

function delay(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function generateRandomIspb() {
  return Math.floor(10000000 + Math.random() * 90000000).toString();
}

const vuIspbMapping = {};
function getVuIspbPair(vuId) {
  if (!vuIspbMapping[vuId]) {
    const pagador = generateRandomIspb();
    let recebedor = generateRandomIspb();
    while (recebedor === pagador) recebedor = generateRandomIspb();
    vuIspbMapping[vuId] = { pagador, recebedor };
  }
  return vuIspbMapping[vuId];
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

function openNotificationStream(ispb, inbox, streamName) {
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

function waitForMatch(inbox, txId, timeoutMs) {
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
        resolve(null);
        return;
      }

      setTimeout(poll, 10);
    }

    poll();
  });
}

async function runTransactionFlow(state, txId) {
  const { ispbPagador, ispbRecebedor, recebedorInbox, pagadorInbox } = state;

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
  const incomingPayment = await waitForMatch(recebedorInbox, txId, MSG_TIMEOUT_MS);
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
  const confirmation = await waitForMatch(pagadorInbox, txId, MSG_TIMEOUT_MS);
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

export default async function () {
  const vuId = exec.vu.idInTest;
  const { pagador: ispbPagador, recebedor: ispbRecebedor } = getVuIspbPair(vuId);

  client.connect(GATEWAY_ADDR, { plaintext: true });

  const recebedorInbox = Object.create(null);
  const pagadorInbox = Object.create(null);
  const inFlight = new Map();

  const recebedorStream = openNotificationStream(ispbRecebedor, recebedorInbox, 'recebedor');
  const pagadorStream = openNotificationStream(ispbPagador, pagadorInbox, 'pagador');

  const state = { ispbPagador, ispbRecebedor, recebedorInbox, pagadorInbox };
  let txSeq = 0;

  try {
    while (exec.scenario.progress < 0.98) {
      if (inFlight.size < MAX_IN_FLIGHT) {
        const txId = `${__VU}-${Date.now()}-${txSeq++}`;
        transactionsStarted.add(1);

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

      await delay(NEW_TX_EVERY_MS);
    }

    while (inFlight.size > 0) {
      await Promise.all(Array.from(inFlight.values()));
    }
  } finally {
    try { recebedorStream.end(); } catch (_) {}
    try { pagadorStream.end(); } catch (_) {}
    try { client.close(); } catch (_) {}
  }
}