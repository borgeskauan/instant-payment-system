package br.kauan.spi.domain.entity.status;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class StatusUpdate {
    private String originalRequestId;
    private String originalPaymentId;
    private PaymentStatus status; // "accepted_and_settled", "rejected", etc.
    private List<Reason> reasons;
}
