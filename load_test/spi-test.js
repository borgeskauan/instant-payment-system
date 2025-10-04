import http from 'k6/http';
import {check, sleep} from 'k6';

export let options = {
    vus: 10,
    duration: '2m',
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

        // console.log(`VU ${vuId} assigned: Pagador=${ispbPagador}, Recebedor=${ispbRecebedor}`);
    }
    return vuIspbMapping[vuId];
}

function fetchMessagesWithRetry(ispb, transactionId, delayInMs) {
    const maxRetries = 10;
    let retries = 0;

    while (retries < maxRetries) {
        let res = http.get(`${BASE_URL}/${ispb}/messages`);

        if (res.status === 200) {
            try {
                const messages = JSON.parse(res.body).content;

                // Flexible matching for our transaction
                const ourMessages = messages.filter(msg => {
                    const msgStr = JSON.stringify(msg).toLowerCase();
                    const searchId = transactionId.toLowerCase();
                    return msgStr.includes(searchId) ||
                        (msg.OrgnlEndToEndId && msg.OrgnlEndToEndId.includes(transactionId)) ||
                        (msg.EndToEndId && msg.EndToEndId.includes(transactionId)) ||
                        (msg.originalEndToEndId && msg.originalEndToEndId.includes(transactionId));
                });

                if (ourMessages.length > 0) {
                    // console.log(`VU ${__VU}: Found ${ourMessages.length} messages for transaction ${transactionId}`);
                    return {
                        success: true,
                        messages: ourMessages
                    };
                }

                // If there are messages but none are ours, log it
                if (messages.length > 0 && ourMessages.length === 0) {
                    // console.log(`VU ${__VU}: Found ${messages.length} messages for ISPB ${ispb}, but none match transaction ${transactionId}`);
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

    return {
        success: false,
        error: `No messages found for transaction ${transactionId} after ${maxRetries} retries`,
        messages: []
    };
}

export default function () {
    const transactionId = `${__VU}-${Date.now()}`;

    // Use VU-specific ISPB pairs
    const ispbPair = getVusIspbPair(__VU);
    const ispbPagador = ispbPair.pagador;
    const ispbRecebedor = ispbPair.recebedor;

    // console.log(`VU ${__VU} starting transaction ${transactionId} with Pagador=${ispbPagador}, Recebedor=${ispbRecebedor}`);

    const pacs008 = generatePacs008(transactionId, ispbPagador, ispbRecebedor);

    // 1. PSP Pagador envia PACS.008
    let res = http.post(`${BASE_URL}/${ispbPagador}/transfer`, JSON.stringify(pacs008), {
        headers: {'Content-Type': 'application/json'}
    });

    const checkResult = check(res, {
        'transfer request accepted by SPI': (r) => r.status === 200
    });

    if (!checkResult) {
        console.error(`VU ${__VU}: Transfer request failed for transaction ${transactionId}`);
        return;
    }

    // 2. PSP Recebedor consulta mensagens com retry para verificar se existe um pedido de aceite (PACS.008)
    const fetchResult = fetchMessagesWithRetry(ispbRecebedor, transactionId, 0.5);

    if (fetchResult.success) {
        // Check if any message contains our transaction
        const hasOurTransaction = fetchResult.messages.some(msg =>
            msg.includes(transactionId) ||
            (msg.OrgnlEndToEndId && msg.OrgnlEndToEndId.includes(transactionId)) ||
            (msg.EndToEndId && msg.EndToEndId.includes(transactionId))
        );

        if (hasOurTransaction) {
            // 3. PSP Recebedor envia aceite PACS.002
            const pacs002 = generatePacs002(transactionId);

            let ack = http.post(`${BASE_URL}/${ispbRecebedor}/transfer/status`, JSON.stringify(pacs002), {
                headers: {'Content-Type': 'application/json'}
            });

            const statusCheck = check(ack, {
                'status report accepted by SPI': (r) => r.status === 200
            });

            if (!statusCheck) {
                console.error(`VU ${__VU}: Status report failed for transaction ${transactionId}`);
            }
        }
    } else {
        console.error(`VU ${__VU}: Failed to fetch messages for transaction ${transactionId}: ${fetchResult.error}`);
    }

    // 4. PSP Pagador consulta mensagens de confirmação com retry
    const confirmResult = fetchMessagesWithRetry(ispbPagador, transactionId, 0.5);

    check(confirmResult, {
        'confirmation received': (result) => result.success
    });

    if (!confirmResult.success) {
        console.error(`VU ${__VU}: No confirmation received for transaction ${transactionId}`);
    }
}