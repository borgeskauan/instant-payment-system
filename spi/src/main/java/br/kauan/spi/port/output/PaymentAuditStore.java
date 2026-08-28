package br.kauan.spi.port.output;

import br.kauan.spi.domain.entity.audit.PaymentAuditEvent;

import java.util.List;

public interface PaymentAuditStore {
    void insertAll(List<PaymentAuditEvent> events);
}
