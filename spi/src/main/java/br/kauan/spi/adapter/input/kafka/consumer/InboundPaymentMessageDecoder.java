package br.kauan.spi.adapter.input.kafka.consumer;

import br.kauan.pix.internal.v1.PaymentRequest;
import br.kauan.pix.internal.v1.PaymentStatusReport;
import br.kauan.spi.adapter.input.kafka.internal.InternalPaymentMessageMapper;
import br.kauan.spi.domain.entity.status.StatusReportCommand;
import br.kauan.spi.domain.entity.transfer.PaymentTransactionCommand;
import br.kauan.spi.domain.services.tracing.SpiTraceEvent;
import br.kauan.spi.domain.services.tracing.SpiTraceRecorder;
import com.google.protobuf.InvalidProtocolBufferException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class InboundPaymentMessageDecoder {

    private final InternalPaymentMessageMapper messageMapper;
    private final SpiTraceRecorder traceRecorder;
    private final boolean forceUnknownProcessingError;

    public InboundPaymentMessageDecoder(
            InternalPaymentMessageMapper messageMapper,
            SpiTraceRecorder traceRecorder,
            @Value("${spi.kafka.force-unknown-processing-error:false}") boolean forceUnknownProcessingError
    ) {
        this.messageMapper = messageMapper;
        this.traceRecorder = traceRecorder;
        this.forceUnknownProcessingError = forceUnknownProcessingError;
    }

    public PaymentTransactionCommand toPaymentTransaction(ConsumerRecord<String, byte[]> record) {
        byte[] payload = record.value();
        if (payload == null || payload.length == 0) {
            throw new InvalidInboundPayloadException("Payment request payload is empty");
        }

        PaymentRequest request;
        try {
            request = PaymentRequest.parseFrom(payload);
        } catch (InvalidProtocolBufferException e) {
            throw new InvalidInboundPayloadException("Failed to parse payment request protobuf", e);
        }

        failIfForcedUnknownProcessingError();

        PaymentTransactionCommand command = messageMapper.toPaymentTransaction(request);
        traceRecorder.record(request.getPaymentId(), SpiTraceEvent.REQUEST_CONSUMED);
        return command;
    }

    public StatusReportCommand toStatusReport(ConsumerRecord<String, byte[]> record) {
        byte[] payload = record.value();
        if (payload == null || payload.length == 0) {
            throw new InvalidInboundPayloadException("Payment status report payload is empty");
        }

        PaymentStatusReport report;
        try {
            report = PaymentStatusReport.parseFrom(payload);
        } catch (InvalidProtocolBufferException e) {
            throw new InvalidInboundPayloadException("Failed to parse payment status report protobuf", e);
        }

        failIfForcedUnknownProcessingError();

        StatusReportCommand command = messageMapper.toStatusReport(report);
        traceRecorder.record(report.getPaymentId(), SpiTraceEvent.STATUS_RECEIVED);
        return command;
    }

    private void failIfForcedUnknownProcessingError() {
        if (forceUnknownProcessingError) {
            throw new IllegalStateException("forced unknown processing failure");
        }
    }
}
