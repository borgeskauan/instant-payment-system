package br.kauan.paymentserviceprovider.domain.entity.transfer;

import br.kauan.paymentserviceprovider.domain.entity.commons.BatchDetails;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class PaymentBatch {
    private BatchDetails batchDetails;
    private List<PaymentTransaction> transactions;
}