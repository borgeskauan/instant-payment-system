import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Counter } from 'k6/metrics';

// Custom metrics - durations will be stored in seconds
const transferRequestDuration = new Trend('transfer_request_duration', true); // true enables time conversion
const fetchMessagesDuration = new Trend('fetch_messages_duration', true);
const statusReportDuration = new Trend('status_report_duration', true);
const confirmationCheckDuration = new Trend('confirmation_check_duration', true);
const totalTransactionDuration = new Trend('total_transaction_duration', true);
const transactionSuccess = new Counter('transaction_success');
const transactionFailure = new Counter('transaction_failure');

export let options = {
    vus: 8000,
    duration: '2m',
    thresholds: {
        total_transaction_duration: ['p(99)<4000']
    }
};

// Static global variable for base URL
const BASE_URL = 'http://localhost:8001';

const transferRequestTemplate = JSON.parse(open('./transfer-request-template.json'));
const statusAcceptanceTemplate = JSON.parse(open('./status-acceptance-template.json'));

// Generate random 8-digit ISPB
function generateRandomIspb() {
    return Math.floor(10000000 + Math.random() * 90000000).toString();
}

// Generate unique PACS.008 XML
function generatePacs008(id, ispbPagador, ispbRecebedor) {
    const payload = JSON.parse(JSON.stringify(transferRequestTemplate));

    const timestamp = Date.now();

    // Update the root level fields
    payload.GrpHdr.MsgId = `MSG${timestamp}_${__VU}_${__ITER}`;
    payload.GrpHdr.CreDtTm = new Date().toISOString();

    // Update the transaction array (note it's an array)
    if (payload.CdtTrfTxInf && payload.CdtTrfTxInf.length > 0) {
        payload.CdtTrfTxInf[0].PmtId.EndToEndId = id;

        // You can also update other fields dynamically if needed
        payload.CdtTrfTxInf[0].IntrBkSttlmAmt.value = (Math.random() * 99_999 + 100).toFixed(2);

        payload.CdtTrfTxInf[0].DbtrAgt.FinInstnId.ClrSysMmbId.MmbId = ispbPagador;
        payload.CdtTrfTxInf[0].CdtrAgt.FinInstnId.ClrSysMmbId.MmbId = ispbRecebedor;
    }

    return payload;
}

// generate PACS.002 (accept)
function generatePacs002(originalEndToEndId) {
    const payload = JSON.parse(JSON.stringify(statusAcceptanceTemplate));
    const timestamp = Date.now();

    // Update header
    payload.GrpHdr.MsgId = `STATUS-MSG-${timestamp}_${__VU}_${__ITER}`;
    payload.GrpHdr.CreDtTm = new Date().toISOString();

    // Update transaction status with original references
    if (payload.TxInfAndSts && payload.TxInfAndSts.length > 0) {
        payload.TxInfAndSts[0].OrgnlEndToEndId = originalEndToEndId;
    }

    return payload;
}

// VU-specific ISPB mapping
const vuIspbMapping = {};

function getVusIspbPair(vuId) {
    if (!vuIspbMapping[vuId]) {
        // Generate unique ISPB pair for this VU
        const ispbPagador = generateRandomIspb();
        let ispbRecebedor = generateRandomIspb();

        // Ensure they're different
        while (ispbRecebedor === ispbPagador) {
            ispbRecebedor = generateRandomIspb();
        }

        vuIspbMapping[vuId] = {
            pagador: ispbPagador,
            recebedor: ispbRecebedor
        };
    }
    return vuIspbMapping[vuId];
}

function fetchMessagesWithRetry(ispb) {
    const delayInMs = 0.1;
    const maxRetries = 10;
    let retries = 0;

    while (retries < maxRetries) {
        let res = http.get(`${BASE_URL}/${ispb}/messages`, { tags: { name: 'fetch-messages' } });

        if (res.status === 200) {
            try {
                const messages = JSON.parse(res.body).content;
                if (messages.length > 0) {
                    return {
                        success: true,
                        messages: messages
                    };
                }

            } catch (e) {
                console.error(`VU ${__VU}: Error parsing messages:`, e);
            }
        } else if (res.status === 404) {
            // No messages at all - expected case, we'll retry
        } else {
            console.error(`VU ${__VU}: Unexpected status ${res.status} when fetching messages`);
        }

        retries++;
        if (retries < maxRetries) {
            sleep(delayInMs);
        }
    }

    console.error(`No messages found after ${maxRetries} retries`);

    return {
        success: false,
        error: `No messages found after ${maxRetries} retries`,
        messages: []
    };
}

export default function () {
    const transactionStartTime = Date.now();
    let operationStartTime;
    const transactionId = `${__VU}-${Date.now()}`;

    // Use VU-specific ISPB pairs
    const ispbPair = getVusIspbPair(__VU);
    const ispbPagador = ispbPair.pagador;
    const ispbRecebedor = ispbPair.recebedor;

    const pacs008 = generatePacs008(transactionId, ispbPagador, ispbRecebedor);

    // 1. PSP Pagador envia PACS.008
    operationStartTime = Date.now();
    let res = http.post(`${BASE_URL}/${ispbPagador}/transfer`, {}, {
        headers: { 'Content-Type': 'application/json' },
        tags: { name: 'transfer-request' }
    });
    transferRequestDuration.add(Date.now() - operationStartTime);

    const checkResult = check(res, {
        'transfer request accepted by SPI': (r) => r.status === 200
    });

    if (!checkResult) {
        console.error(`VU ${__VU}: Transfer request failed for transaction ${transactionId}`);
        transactionFailure.add(1);
        return;
    }

    // 2. PSP Recebedor consulta mensagens com retry para verificar se existe um pedido de aceite (PACS.008)
    operationStartTime = Date.now();
    const fetchResult = fetchMessagesWithRetry(ispbRecebedor);
    fetchMessagesDuration.add(Date.now() - operationStartTime);

    if (fetchResult.success) {
        // 3. PSP Recebedor envia aceite PACS.002
        const pacs002 = generatePacs002(transactionId);

        operationStartTime = Date.now();
        let ack = http.post(`${BASE_URL}/${ispbRecebedor}/transfer/status`, {}, {
            headers: { 'Content-Type': 'application/json' },
            tags: { name: 'status-report' }
        });
        statusReportDuration.add(Date.now() - operationStartTime);

        const statusCheck = check(ack, {
            'status report accepted by SPI': (r) => r.status === 200
        });

        if (!statusCheck) {
            console.error(`VU ${__VU}: Status report failed for transaction ${transactionId}`);
        }
    } else {
        console.error(`VU ${__VU}: Failed to fetch messages for transaction ${transactionId}: ${fetchResult.error}`);
    }

    // 4. PSP Pagador consulta mensagens de confirmação com retry
    operationStartTime = Date.now();
    const confirmResult = fetchMessagesWithRetry(ispbPagador);
    confirmationCheckDuration.add(Date.now() - operationStartTime);

    const confirmationCheck = check(confirmResult, {
        'confirmation received': (result) => result.success
    });

    // Record total transaction duration
    const totalDuration = Date.now() - transactionStartTime;
    totalTransactionDuration.add(totalDuration);

    if (confirmationCheck) {
        transactionSuccess.add(1);
    } else {
        console.error(`VU ${__VU}: No confirmation received for transaction ${transactionId}`);
        transactionFailure.add(1);
    }
}