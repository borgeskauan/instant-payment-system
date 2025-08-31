package br.kauan.spi.domain.entity.transfer;

import br.kauan.spi.domain.entity.commons.BatchDetails;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class PaymentBatch {
    private BatchDetails batchDetails;
    private List<PaymentTransaction> transactions;
}