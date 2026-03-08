import http from 'k6/http';
import grpc from 'k6/net/grpc';
import exec from 'k6/execution';
import { sleep, check } from 'k6';
import { Trend, Counter } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8001';
const GATEWAY_ADDR = __ENV.GATEWAY_ADDR || 'localhost:9090';
const MSG_TIMEOUT_MS = parseInt(__ENV.MSG_TIMEOUT_MS || '3000', 10);
const SESSION_TX_INTERVAL_MS = parseInt(__ENV.SESSION_TX_INTERVAL_MS || '1000', 10);

// init context
const client = new grpc.Client();
client.load([__ENV.PROTO_DIR || '.'], 'notification.proto');

const transferRequestTemplate = JSON.parse(open('./transfer-request-template.json'));
const statusAcceptanceTemplate = JSON.parse(open('./status-acceptance-template.json'));

export const options = {
  scenarios: {
    psp_sessions: {
      executor: 'constant-vus',
      vus: 1,
      duration: '2m',
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

function openNotificationStream(ispb, holder, streamName) {
  const stream = new grpc.Stream(
    client,
    'notification.NotificationGateway/StreamNotifications'
  );

  stream.on('data', (msg) => {
    holder.message = msg;
  });

  stream.on('error', (err) => {
    const text = JSON.stringify(err);
    if (text.includes('canceled by client')) return;

    holder.error = err;
    console.error(`VU ${__VU}: ${streamName} stream error for ISPB ${ispb}: ${text}`);
  });

  stream.on('end', () => {
    console.warn(`VU ${__VU}: ${streamName} stream ended for ISPB ${ispb}`);
  });

  stream.write({ ispb });
  return stream;
}

function resetHolder(holder) {
  holder.message = null;
  holder.error = null;
}

function waitForNextMessage(ispb, holder, timeoutMs) {
  console.log(`VU ${__VU}: awaiting notification for ISPB ${ispb} (timeout: ${timeoutMs}ms)`);
  const deadline = Date.now() + timeoutMs;

  return new Promise((resolve) => {
    function poll() {
      if (holder.message !== null) {
        const msg = holder.message;
        holder.message = null;
        console.log(`VU ${__VU}: received notification for ISPB ${ispb}`);
        resolve(msg);
        return;
      }

      if (holder.error !== null || Date.now() >= deadline) {
        console.warn(`VU ${__VU}: timed out waiting for notification for ISPB ${ispb}`);
        resolve(null);
        return;
      }

      setTimeout(poll, 10);
    }

    poll();
  });
}

export default async function () {
  const vuId = exec.vu.idInTest;
  const { pagador: ispbPagador, recebedor: ispbRecebedor } = getVuIspbPair(vuId);

  client.connect(GATEWAY_ADDR, { plaintext: true });

  const recebedorHolder = { message: null, error: null };
  const pagadorHolder = { message: null, error: null };

  const recebedorStream = openNotificationStream(ispbRecebedor, recebedorHolder, 'recebedor');
  const pagadorStream = openNotificationStream(ispbPagador, pagadorHolder, 'pagador');

  console.log(`VU ${__VU}: PSP session started. pagador=${ispbPagador}, recebedor=${ispbRecebedor}`);

  try {
    while (exec.scenario.progress < 0.98) {
      const transactionStartTime = Date.now();
      let operationStartTime;
      const transactionId = `${__VU}-${Date.now()}`;

      resetHolder(recebedorHolder);
      resetHolder(pagadorHolder);

      const pacs008 = generatePacs008(transactionId, ispbPagador, ispbRecebedor);

      operationStartTime = Date.now();
      console.log(`VU ${__VU}: sending pacs.008`);
      const transferRes = http.post(
        `${BASE_URL}/${ispbPagador}/transfer`,
        JSON.stringify(pacs008),
        {
          headers: { 'Content-Type': 'application/octet-stream' },
          tags: { name: 'transfer-request' },
        }
      );
      transferRequestDuration.add(Date.now() - operationStartTime);
      console.log(`VU ${__VU}: pacs.008 response status=${transferRes.status}`);

      const transferOk = check(transferRes, {
        'transfer request accepted by SPI': (r) => r.status === 200,
      });

      if (!transferOk) {
        transactionFailure.add(1);
        sleep(SESSION_TX_INTERVAL_MS / 1000);
        continue;
      }

      operationStartTime = Date.now();
      const incomingPayment = await waitForNextMessage(
        ispbRecebedor,
        recebedorHolder,
        MSG_TIMEOUT_MS
      );
      fetchMessagesDuration.add(Date.now() - operationStartTime);

      const receivedOk = check(incomingPayment, {
        'pacs.008 notification received by recebedor': (msg) => msg !== null,
      });

      if (!receivedOk) {
        transactionFailure.add(1);
        sleep(SESSION_TX_INTERVAL_MS / 1000);
        continue;
      }

      const pacs002 = generatePacs002(transactionId);

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
        sleep(SESSION_TX_INTERVAL_MS / 1000);
        continue;
      }

      operationStartTime = Date.now();
      const confirmation = await waitForNextMessage(
        ispbPagador,
        pagadorHolder,
        MSG_TIMEOUT_MS
      );
      confirmationCheckDuration.add(Date.now() - operationStartTime);

      const confirmedOk = check(confirmation, {
        'pacs.002 confirmation received by pagador': (msg) => msg !== null,
      });

      totalTransactionDuration.add(Date.now() - transactionStartTime);

      if (confirmedOk) {
        transactionSuccess.add(1);
      } else {
        transactionFailure.add(1);
      }

      sleep(SESSION_TX_INTERVAL_MS / 1000);
    }
  } finally {
    try { recebedorStream.end(); } catch (_) {}
    try { pagadorStream.end(); } catch (_) {}
    try { client.close(); } catch (_) {}
    console.log(`VU ${__VU}: PSP session ended`);
  }
}