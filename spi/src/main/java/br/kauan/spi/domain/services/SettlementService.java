package br.kauan.spi.domain.services;

import br.kauan.spi.domain.entity.status.PaymentStatus;
import br.kauan.spi.domain.entity.transfer.PaymentTransactionCommand;
import br.kauan.spi.port.output.SettlementRepository;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
public class SettlementService {

    private final SettlementRepository settlementRepository;

    public SettlementService(SettlementRepository settlementRepository) {
        this.settlementRepository = settlementRepository;
    }

    @Transactional
    public SettlementResult tryMakeSettlements(List<String> paymentIds) {
      List<PaymentTransactionCommand> settledOrAlreadySettledPayments =
              settlementRepository.settleAcceptedPaymentsIdempotently(
                      paymentIds,
                      PaymentStatus.WAITING_ACCEPTANCE,
                      PaymentStatus.ACCEPTED_AND_SETTLED
              );

      Set<String> settledOrAlreadySettledPaymentIds =
              new HashSet<>((int) (settledOrAlreadySettledPayments.size() / 0.75f) + 1);

      for (PaymentTransactionCommand payment : settledOrAlreadySettledPayments) {
          settledOrAlreadySettledPaymentIds.add(payment.getPaymentId());
      }

      Set<String> notSettledPaymentIds = new LinkedHashSet<>(paymentIds);
      notSettledPaymentIds.removeAll(settledOrAlreadySettledPaymentIds);

      return new SettlementResult(
              settledOrAlreadySettledPayments,
              new ArrayList<>(notSettledPaymentIds)
      );
    }
}
