package br.kauan.spi.domain.services.notification.dto;

import br.kauan.spi.domain.entity.status.StatusReport;
import br.kauan.spi.domain.entity.transfer.PaymentTransaction;

import java.util.ArrayList;
import java.util.List;

public record InstitutionMessages(List<StatusReport> statuses, List<PaymentTransaction> transactions) {
    
    public InstitutionMessages() {
        this(new ArrayList<>(), new ArrayList<>());
    }

    public boolean isEmpty() {
        return statuses.isEmpty() && transactions.isEmpty();
    }
}