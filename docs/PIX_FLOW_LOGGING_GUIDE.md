# PIX Flow Logging Guide

This document describes the comprehensive logging added to track the PIX instant payment flow through all system components.

## Log Format Convention

All logs follow a structured format with **[PIX FLOW - Step X]** prefix to easily track the payment sequence:

- `[PIX FLOW START]` - Entry point from client
- `[PIX FLOW - Step X]` - Sequential steps in the flow
- `[PIX FLOW - DICT Query]` - DICT lookups
- `[PIX FLOW - Settlement]` - Settlement operations
- `[PIX FLOW COMPLETE]` - Successful completion
- `[PIX FLOW - Error]` - Error conditions
- `[PIX FLOW - Rejection]` - Payment rejections

## Complete Flow Sequence

### Step 1: Cliente Pagador → PSP Pagador (Transfer Preview)

**Entry Point:** `PspController.processPayment()`
```
=== [PIX FLOW START - Preview] Cliente Pagador requesting transfer preview for PIX key: {key} ===
```

**Service:** `PspService.fetchPaymentPreview()`
```
[PIX FLOW - Step 1] Fetching payment preview for PIX key: {key}
[PIX FLOW - DICT Query] Querying DICT for PIX key: {key}
[PIX FLOW - DICT Query] PIX key found. Bank: {bank}, Account holder: {name}
[PIX FLOW - Step 1] Payment preview fetched successfully. Receiver: {name}
```

---

### Step 2: Cliente Pagador → PSP Pagador (Transfer Execution)

**Entry Point:** `PspController.requestTransfer()`
```
=== [PIX FLOW START - Execution] Cliente Pagador executing transfer. Amount: {amount}, Receiver: {name} ===
```

**Service:** `PspService.requestTransfer()`
```
[PIX FLOW - Step 2] PSP Pagador - Initiating transfer request. Customer: {id}, Amount: {amount}
[PIX FLOW - Step 2] Sender details retrieved: {name}
[PIX FLOW - Step 2] Sending transfer request to Central Transfer Service
```

---

### Step 3: PSP Pagador → SPI (via kafka-producer)

**Service:** `TransferRequestService.requestTransfer()`
```
[PIX FLOW - Step 3] PSP Pagador preparing transfer request. Amount: {amount}, Receiver: {name}
[PIX FLOW - Step 3] Saved payment transaction with ID: {id}
[PIX FLOW - Step 3] Sending PACS.008 transfer request to kafka-producer for bank: {bank}, payload size: {bytes} bytes
[PIX FLOW - Step 3] Transfer request sent successfully to kafka-producer (will be forwarded to SPI)
```

**Controller:** `PaymentController.transfer()` (kafka-producer)
```
[PIX FLOW - Step 3] Kafka Producer received transfer request for ISPB: {ispb}, payload size: {bytes} bytes
[PIX FLOW - Step 3] Forwarding PACS.008 message to Kafka topic
```

**Service:** `QueueService.sendBytes()`
```
Sending {bytes} bytes to Kafka topic: high-load-binary-topic
Successfully sent message to Kafka, partition: {partition}, offset: {offset}
```

---

### Step 4: SPI → PSP Recebedor

**Consumer:** `PaymentMessageConsumer.consumeMessage()` (SPI)
```
Received message from Kafka topic 'high-load-binary-topic', size: {bytes} bytes
Detected payment transaction (pacs.008) message
Processing payment transaction batch for ISPB: {ispb}, transactions: {count}
```

**Service:** `PaymentTransactionProcessorService.processTransaction()` (SPI)
```
[PIX FLOW - Step 3] SPI received transaction request. Payment ID: {id}, Amount: {amount}
[PIX FLOW - Step 3] Transaction saved with status WAITING_ACCEPTANCE
[PIX FLOW - Step 4] SPI forwarding acceptance request to PSP Recebedor (Bank: {bank})
```

**Service:** `NotificationOrchestrator.sendAcceptanceRequest()` (SPI)
```
[PIX FLOW - Step 4] Sending acceptance request (PACS.008) to PSP Recebedor. ISPB: {ispb}, Payment ID: {id}
[PIX FLOW - Step 4] Acceptance request queued for delivery via Kafka
```

**Consumer:** `NotificationConsumer.consumeNotification()` (PSP Recebedor)
```
Received notification for ISPB: {ispb} from partition: {partition}, offset: {offset}
Processing incoming payment batch with {count} transactions
```

**Service:** `IncomingTransactionService.handleTransferRequestBatch()` (PSP Recebedor)
```
[PIX FLOW - Step 4] PSP Recebedor received payment batch with {count} transactions from SPI
[PIX FLOW - Step 4] PSP Recebedor processing incoming transaction: {id}, Amount: {amount}
[PIX FLOW - Step 4] PSP Recebedor saved incoming transaction: {id}. Auto-approving payment.
```

---

### Step 5: PSP Recebedor → SPI (Acceptance)

**Service:** `IncomingTransactionService.processIncomingTransaction()` (PSP Recebedor)
```
[PIX FLOW - Step 5] PSP Recebedor sending acceptance (PACS.002) to SPI. Status: ACCEPTED_IN_PROCESS
[PIX FLOW - Step 5] Acceptance sent successfully to kafka-producer (will be forwarded to SPI)
```

**Controller:** `PaymentController.transferStatus()` (kafka-producer)
```
[PIX FLOW - Step 5] Kafka Producer received status report for ISPB: {ispb}, payload size: {bytes} bytes
[PIX FLOW - Step 5] Forwarding PACS.002 message to Kafka topic
```

**Consumer:** `PaymentMessageConsumer.processStatusReport()` (SPI)
```
Detected status report (pacs.002) message
Processing status report batch for ISPB: {ispb}, reports: {count}
```

**Service:** `PaymentTransactionProcessorService.processStatusReport()` (SPI)
```
[PIX FLOW - Step 5] SPI received status report. Payment ID: {id}, Status: ACCEPTED_IN_PROCESS
[PIX FLOW - Step 5] Payment accepted by PSP Recebedor. Proceeding with settlement.
```

---

### Step 6: SPI Settlement (BCB PI Accounts)

**Service:** `PaymentTransactionProcessorService.processAcceptedPayment()` (SPI)
```
[PIX FLOW - Step 6] Payment status updated to ACCEPTED_IN_PROCESS
[PIX FLOW - Step 6] SPI initiating settlement via PI accounts at BCB. Payment ID: {id}, Amount: {amount}
```

**Service:** `SettlementService.makeSettlement()` (SPI)
```
[PIX FLOW - Step 6] Settlement completed in SPI (BCB PI accounts): {amount} from {sender} to {receiver}
[PIX FLOW - Step 6] Updated PI account balances - {sender}: {balance1}, {receiver}: {balance2}
```

**Service:** `PaymentTransactionProcessorService.processAcceptedPayment()` (SPI)
```
[PIX FLOW - Step 6] Settlement completed successfully at SPI
```

---

### Step 7: SPI → PSPs (Confirmation Notifications)

**Service:** `PaymentTransactionProcessorService.processAcceptedPayment()` (SPI)
```
[PIX FLOW - Step 7] SPI sending confirmation notifications to both PSPs
```

**Service:** `NotificationOrchestrator.sendConfirmationNotification()` (SPI)
```
[PIX FLOW - Step 7] Building confirmation notifications for payment: {id}
[PIX FLOW - Step 7] Receiver ISPB: {ispb1}, Sender ISPB: {ispb2}
[PIX FLOW - Step 7] Confirmation notification sent to PSP Recebedor ({ispb})
[PIX FLOW - Step 7] Confirmation notification sent to PSP Pagador ({ispb})
```

**Service:** `PaymentTransactionProcessorService.processAcceptedPayment()` (SPI)
```
[PIX FLOW - Complete] Payment {id} fully settled and confirmed
```

---

### Step 8: PSP Recebedor → Cliente Recebedor (Credit)

**Consumer:** `NotificationConsumer.processStatusReport()` (PSP Recebedor)
```
Processing status batch with {count} reports
```

**Service:** `StatusProcessingService.handleStatusBatch()` (PSP Recebedor)
```
[PIX FLOW - Step 8] PSP Pagador received status batch with {count} reports from SPI
[PIX FLOW - Step 8] PSP Pagador processing status report for payment: {id}, Status: {status}
[PIX FLOW - Step 8/9] PSP handling settlement for payment {id} with status: ACCEPTED_AND_SETTLED_FOR_RECEIVER
[PIX FLOW - Step 8] PSP Recebedor crediting receiver account
```

**Service:** `SettlementService.creditReceiverAccount()` (PSP Recebedor)
```
[PIX FLOW - Step 8] Cliente Recebedor credited: Amount {amount} to account {id}
```

**Service:** `BankAccountPartyService.addAmountToAccount()` (PSP Recebedor)
```
[PIX FLOW - Settlement] Credited amount {amount} to account {id}. Balance: {old} -> {new}
=== [PIX FLOW COMPLETE - Receiver] Cliente Recebedor credited successfully ===
```

---

### Step 9: PSP Pagador → Cliente Pagador (Debit)

**Consumer:** `NotificationConsumer.processStatusReport()` (PSP Pagador)
```
Processing status batch with {count} reports
```

**Service:** `StatusProcessingService.handleStatusBatch()` (PSP Pagador)
```
[PIX FLOW - Step 8] PSP Pagador received status batch with {count} reports from SPI
[PIX FLOW - Step 8] PSP Pagador processing status report for payment: {id}, Status: {status}
[PIX FLOW - Step 8/9] PSP handling settlement for payment {id} with status: ACCEPTED_AND_SETTLED_FOR_SENDER
[PIX FLOW - Step 9] PSP Pagador debiting sender account
```

**Service:** `SettlementService.debitSenderAccount()` (PSP Pagador)
```
[PIX FLOW - Step 9] Cliente Pagador debited: Amount {amount} from account {id}
```

**Service:** `BankAccountPartyService.removeAmountFromAccount()` (PSP Pagador)
```
[PIX FLOW - Settlement] Debited amount {amount} from account {id}. Balance: {old} -> {new}
=== [PIX FLOW COMPLETE - Sender] Cliente Pagador debited successfully ===
```

---

## Error Handling

### Rejection Flow

**Service:** `PaymentTransactionProcessorService.processRejectedPayment()` (SPI)
```
[PIX FLOW - Rejection] Processing rejected payment. Payment ID: {id}
[PIX FLOW - Rejection] Sending rejection notification to PSP Pagador
```

**Service:** `StatusProcessingService.processStatusReport()` (PSP Pagador)
```
[PIX FLOW - Step 8] Payment {id} was rejected
```

### Error Conditions

```
[PIX FLOW - Error] Failed to serialize or send transfer request
[PIX FLOW - Error] An error occurred while processing the payment with ID {id}
[PIX FLOW - Error] Failed to send confirmation notification for payment: {id}
```

---

## Log Levels

- **INFO**: Main flow steps, key decisions, successful operations
- **DEBUG**: Detailed step information, intermediate states
- **WARN**: Rejections, unknown message types, unhandled statuses
- **ERROR**: Exceptions, failures, critical errors

---

## Monitoring Tips

### Track a Complete Payment Flow

Search for a specific payment ID across all logs:
```bash
grep "Payment ID: ABC123" *.log
```

### Monitor Flow Steps

```bash
# See all step 1 operations (Preview)
grep "\[PIX FLOW - Step 1\]" *.log

# See all settlements
grep "\[PIX FLOW - Settlement\]" *.log

# See all completions
grep "\[PIX FLOW COMPLETE\]" *.log
```

### Monitor DICT Queries

```bash
grep "\[PIX FLOW - DICT Query\]" *.log
```

### Monitor Errors

```bash
grep "\[PIX FLOW - Error\]" *.log
grep "\[PIX FLOW - Rejection\]" *.log
```

---

## Services Modified

### payment-service-provider
- `PspController` - Entry points with flow start markers
- `PspService` - Transfer initiation (Steps 1-2)
- `BankAccountPartyService` - DICT queries and account operations
- `TransferRequestService` - PACS.008 message sending (Step 3)
- `IncomingTransactionService` - Acceptance handling (Steps 4-5)
- `StatusProcessingService` - Status report processing (Steps 8-9)
- `SettlementService` - Final account debits/credits (Steps 8-9)
- `NotificationConsumer` - Kafka message consumption

### spi
- `PaymentTransactionProcessorService` - Transaction and status processing (Steps 3-7)
- `NotificationOrchestrator` - Notification building and sending (Steps 4, 7)
- `NotificationService` - Notification coordination
- `SettlementService` - BCB PI account settlement (Step 6)
- `PaymentMessageConsumer` - Kafka message consumption

### kafka-producer
- `PaymentController` - Message forwarding with flow tracking
- `QueueService` - Kafka publishing

---

## Example Complete Flow Log Output

```
=== [PIX FLOW START - Execution] Cliente Pagador executing transfer. Amount: 100.00, Receiver: João ===
[PIX FLOW - Step 2] PSP Pagador - Initiating transfer request. Customer: cust123, Amount: 100.00
[PIX FLOW - Step 3] PSP Pagador preparing transfer request. Amount: 100.00, Receiver: João
[PIX FLOW - Step 3] Sending PACS.008 transfer request to kafka-producer
[PIX FLOW - Step 3] SPI received transaction request. Payment ID: pay456, Amount: 100.00
[PIX FLOW - Step 4] SPI forwarding acceptance request to PSP Recebedor (Bank: 00000002)
[PIX FLOW - Step 4] PSP Recebedor processing incoming transaction: pay456, Amount: 100.00
[PIX FLOW - Step 5] PSP Recebedor sending acceptance (PACS.002) to SPI
[PIX FLOW - Step 5] SPI received status report. Payment ID: pay456, Status: ACCEPTED_IN_PROCESS
[PIX FLOW - Step 6] SPI initiating settlement via PI accounts at BCB
[PIX FLOW - Step 6] Settlement completed in SPI (BCB PI accounts): 100.00 from 00000001 to 00000002
[PIX FLOW - Step 7] SPI sending confirmation notifications to both PSPs
[PIX FLOW - Step 8] PSP Recebedor crediting receiver account
[PIX FLOW - Settlement] Credited amount 100.00 to account acc789. Balance: 500.00 -> 600.00
=== [PIX FLOW COMPLETE - Receiver] Cliente Recebedor credited successfully ===
[PIX FLOW - Step 9] PSP Pagador debiting sender account
[PIX FLOW - Settlement] Debited amount 100.00 from account acc123. Balance: 1000.00 -> 900.00
=== [PIX FLOW COMPLETE - Sender] Cliente Pagador debited successfully ===
```
