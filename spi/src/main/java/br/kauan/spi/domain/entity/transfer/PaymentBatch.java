package br.kauan.spi.domain.entity.transfer;

import lombok.Data;

import java.util.List;

@Data
public class PaymentBatch {
    private BatchDetails batchDetails;
    private List<PaymentTransaction> transactions;
}