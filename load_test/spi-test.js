import http from 'k6/http';
import {check, sleep} from 'k6';

export let options = {
    vus: 100,
    duration: '2m',
};

// Static global variable for base URL
const BASE_URL = 'http://spi:8080';

const transferRequestTemplate = JSON.parse(open('./transfer-request-template.json'));
const statusAcceptanceTemplate = JSON.parse(open('./status-acceptance-template.json'));

// generate unique PACS.008 XML
function generatePacs008(id) {
    const payload = JSON.parse(JSON.stringify(transferRequestTemplate));

    const timestamp = Date.now();
    const uniqueMsgId = `MSG${timestamp}_${__VU}_${__ITER}`;
    const uniqueEndToEndId = `PIX-TRANSFER-${timestamp}_${__VU}_${__ITER}`;

    // Update the root level fields
    payload.GrpHdr.MsgId = uniqueMsgId;
    payload.GrpHdr.CreDtTm = new Date().toISOString();

    // Update the transaction array (note it's an array)
    if (payload.CdtTrfTxInf && payload.CdtTrfTxInf.length > 0) {
        payload.CdtTrfTxInf[0].PmtId.EndToEndId = uniqueEndToEndId;

        // You can also update other fields dynamically if needed
        payload.CdtTrfTxInf[0].IntrBkSttlmAmt.value = Math.random() * 99_999 + 100; // Random amount between 100-1100
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

// Function to fetch messages with retry logic
// TODO: If we are running tests concurrently, then this test cycle might retrieve the status report of another test cycle, which might cause problems.
function fetchMessagesWithRetry(ispb, maxAttempts = 10, retryDelay = 0.5) {
    let attempts = 0;

    while (attempts < maxAttempts) {
        let msgs = http.get(`${BASE_URL}/${ispb}/messages`);

        check(msgs, {
            'messages fetched successfully': (r) => r.status === 200
        });

        if (msgs.status === 200) {
            try {
                // Parse the response to check if there are messages
                const messages = JSON.parse(msgs.body);

                // Check if content array exists and has messages
                if (messages.content && messages.content.length > 0) {
                    return {success: true, response: msgs, messages: messages.content};
                }
            } catch (e) {
                // If JSON parsing fails, check if body contains the expected XML tag
                if (msgs.body.includes('<FIToFICstmrCdtTrf>')) {
                    return {success: true, response: msgs, messages: [msgs.body]};
                }
            }
        }

        attempts++;
        if (attempts < maxAttempts) {
            sleep(retryDelay);
        }
    }

    return {
        success: false,
        response: null,
        error: `No messages found after ${maxAttempts} attempts`
    };
}

const ispbPagador = "12345678";
const ispbRecebedor = "87654321";

export default function () {
    const trxId = `${__VU}-${Date.now()}`;
    const pacs008 = generatePacs008(trxId);

    // 1. PSP Pagador envia PACS.008
    let res = http.post(`${BASE_URL}/${ispbPagador}/transfer`, pacs008, {
        headers: {'Content-Type': 'application/json'},
    });

    check(res, {'transfer request accepted by SPI': (r) => r.status === 200});

    // 2. PSP Recebedor consulta mensagens com retry
    const fetchResult = fetchMessagesWithRetry(ispbRecebedor, 10, 0.5);

    if (fetchResult.success) {
        // Check if any message contains our transaction
        const hasOurTransaction = fetchResult.messages.some(msg =>
            msg.includes(trxId) || msg.includes('<FIToFICstmrCdtTrf>')
        );

        if (hasOurTransaction) {
            // 3. PSP Recebedor envia aceite PACS.002
            const pacs002 = generatePacs002(trxId);
            let ack = http.post(`${BASE_URL}/${ispbRecebedor}/transfer/status`, pacs002, {
                headers: {'Content-Type': 'application/json'},
            });
            check(ack, {'status accepted': (r) => r.status === 200});
        }
    }

    // 4. PSP Pagador consulta mensagens de confirmação com retry
    const confirmResult = fetchMessagesWithRetry(ispbPagador, 10, 0.5);
    check(confirmResult, {
        'confirmation received': (result) => result.success
    });

    sleep(0.2);
}