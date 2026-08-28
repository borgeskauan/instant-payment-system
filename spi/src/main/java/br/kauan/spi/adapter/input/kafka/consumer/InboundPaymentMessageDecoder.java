package br.kauan.spi.adapter.input.kafka.consumer;

import br.kauan.pix.internal.v1.PaymentRequest;
import br.kauan.pix.internal.v1.PaymentStatusReport;
import br.kauan.spi.adapter.input.kafka.internal.InternalPaymentMessageMapper;
import br.kauan.spi.domain.entity.status.StatusReportCommand;
import br.kauan.spi.domain.entity.transfer.PaymentTransactionCommand;
import br.kauan.spi.domain.services.tracing.SpiPaymentStage;
import br.kauan.spi.domain.services.tracing.SpiPaymentStageEvent;
import com.google.protobuf.InvalidProtocolBufferException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.stereotype.Component;

@Component
public class InboundPaymentMessageDecoder {

    private final InternalPaymentMessageMapper messageMapper;

    public InboundPaymentMessageDecoder(InternalPaymentMessageMapper messageMapper) {
        this.messageMapper = messageMapper;
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

        PaymentTransactionCommand command = messageMapper.toPaymentTransaction(request);
        SpiPaymentStageEvent.record(request.getPaymentId(), SpiPaymentStage.REQUEST_CONSUMED);
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

        StatusReportCommand command = messageMapper.toStatusReport(report);
        SpiPaymentStageEvent.record(report.getPaymentId(), SpiPaymentStage.STATUS_RECEIVED);
        return command;
    }
}
