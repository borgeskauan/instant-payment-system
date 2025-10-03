import http from 'k6/http';
import {check, sleep} from 'k6';

export let options = {
    vus: 2,
    duration: '2m',
};

// Static global variable for base URL
const BASE_URL = 'http://localhost:8001';

const transferRequestTemplate = JSON.parse(open('./transfer-request-template.json'));
const statusAcceptanceTemplate = JSON.parse(open('./status-acceptance-template.json'));

// generate unique PACS.008 XML
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

// Global map to store unused messages by ISPB
let unusedMessagesMap = new Map();

// Function to fetch messages with retry logic, looking for specific transaction ID
function fetchMessagesWithRetry(ispb, transactionId, maxAttempts = 10, retryDelay = 0.1) {
    let attempts = 0;

    // First, check if we already have the target message in the global map
    if (unusedMessagesMap.has(ispb)) {
        const unusedMessages = unusedMessagesMap.get(ispb);

        // Look for our transaction ID in the unused messages
        const targetMessageIndex = findMessageByTransactionId(unusedMessages, transactionId);

        if (targetMessageIndex !== -1) {
            // Found it! Remove from unused messages and return it
            const targetMessage = unusedMessages.splice(targetMessageIndex, 1)[0];

            // Update the map (remove if empty, otherwise keep the remaining messages)
            if (unusedMessages.length === 0) {
                unusedMessagesMap.delete(ispb);
            } else {
                unusedMessagesMap.set(ispb, unusedMessages);
            }

            return {
                success: true,
                response: null, // No HTTP response since we got it from cache
                messages: [targetMessage],
                source: 'cache'
            };
        }
    }

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
                    return processMessages(messages.content, ispb, transactionId, msgs);
                }
            } catch (e) {
                // If JSON parsing fails, check if body contains the expected XML tag
                if (msgs.body.includes('<FIToFICstmrCdtTrf>')) {
                    return processMessages([msgs.body], ispb, transactionId, msgs);
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
        error: `No messages found after ${maxAttempts} attempts`,
        source: 'api'
    };
}

// Helper function to process messages and find the target transaction ID
function processMessages(messages, ispb, transactionId, httpResponse) {
    const targetMessages = [];
    const nonTargetMessages = [];

    // Separate messages: those with our transaction ID vs others
    messages.forEach(message => {
        if (containsTransactionId(message, transactionId)) {
            targetMessages.push(message);
        } else {
            nonTargetMessages.push(message);
        }
    });

    // Store non-target messages in global map for future use
    if (nonTargetMessages.length > 0) {
        if (unusedMessagesMap.has(ispb)) {
            // Merge with existing unused messages
            const existingMessages = unusedMessagesMap.get(ispb);
            unusedMessagesMap.set(ispb, existingMessages.concat(nonTargetMessages));
        } else {
            // Create new entry
            unusedMessagesMap.set(ispb, nonTargetMessages);
        }
    }

    // If we found our target message, return it
    if (targetMessages.length > 0) {
        return {
            success: true,
            response: httpResponse,
            messages: targetMessages,
            source: 'api'
        };
    }

    // No target message found in this batch
    return {
        success: false,
        response: httpResponse,
        error: `Transaction ID ${transactionId} not found in current batch`,
        source: 'api'
    };
}

// Helper function to check if a message contains the transaction ID
function containsTransactionId(message, transactionId) {
    if (typeof message === 'string') {
        // For XML messages, look for the transaction ID in the XML
        return message.includes(transactionId);
    } else if (typeof message === 'object') {
        // For JSON messages, recursively search for the transaction ID
        return JSON.stringify(message).includes(transactionId);
    }
    return false;
}

// Helper function to find message by transaction ID in an array
function findMessageByTransactionId(messages, transactionId) {
    for (let i = 0; i < messages.length; i++) {
        if (containsTransactionId(messages[i], transactionId)) {
            return i;
        }
    }
    return -1;
}

// Utility function to check what's in the global map (for debugging)
// function getUnusedMessagesSummary() {
//     const summary = {};
//     for (let [ispb, messages] of unusedMessagesMap.entries()) {
//         summary[ispb] = messages.length;
//     }
//     return summary;
// }
//
// // Utility function to clear the global map (for test cleanup)
// function clearUnusedMessagesMap() {
//     unusedMessagesMap.clear();
// }

const institutionsCodes = ["12345678", "87654321", "11111111", "22222222", "33333333", "44444444", "55555555", "66666666"];

const generateDifferentIspb = function (previousIspb) {
    let candidateIsbp = institutionsCodes[Math.floor(Math.random() * institutionsCodes.length)];
    if (!previousIspb) {
        return candidateIsbp;
    }

    while (previousIspb === candidateIsbp) {
        candidateIsbp = generateDifferentIspb(previousIspb);
    }

    return candidateIsbp;
};

export default function () {
    const transactionId = `${__VU}-${Date.now()}`;

    const ispbPagador = generateDifferentIspb();
    const ispbRecebedor = generateDifferentIspb(ispbPagador);

    const pacs008 = generatePacs008(transactionId, ispbPagador, ispbRecebedor);

    // console.log("Request being sent:", pacs008);

    // 1. PSP Pagador envia PACS.008
    let res = http.post(`${BASE_URL}/${ispbPagador}/transfer`, JSON.stringify(pacs008), {
        headers: {'Content-Type': 'application/json'}
    });

    const checkResult = check(res, {
        'transfer request accepted by SPI': (r) => r.status === 200
    });

    if (!checkResult) {
        fail('Transfer request failed - status not 200');
    }

    // 2. PSP Recebedor consulta mensagens com retry para verificar se existe um pedido de aceite (PACS.008)
    const fetchResult = fetchMessagesWithRetry(ispbRecebedor, transactionId, 10, 0.5);

    if (fetchResult.success) {
        // Check if any message contains our transaction
        const hasOurTransaction = fetchResult.messages.some(msg =>
            msg.includes(transactionId) || msg.includes('EndToEndId')
        );

        if (hasOurTransaction) {
            // 3. PSP Recebedor envia aceite PACS.002
            const pacs002 = generatePacs002(transactionId);

            // console.log("Status report being sent:", pacs002);

            let ack = http.post(`${BASE_URL}/${ispbRecebedor}/transfer/status`, JSON.stringify(pacs002), {
                headers: {'Content-Type': 'application/json'}
            });
            check(ack, {'status report accepted by SPI': (r) => r.status === 200});
        }
    }

    // 4. PSP Pagador consulta mensagens de confirmação com retry
    const confirmResult = fetchMessagesWithRetry(ispbPagador, transactionId, 10, 0.5);
    if (!confirmResult.success) {
        console.error(confirmResult.error);
    }

    check(confirmResult, {
        'confirmation received': (result) => result.success
    });
}