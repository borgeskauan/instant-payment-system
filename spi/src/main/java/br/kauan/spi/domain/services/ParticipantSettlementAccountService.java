package br.kauan.spi.domain.services;

import br.kauan.spi.port.output.FundsRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
public class ParticipantSettlementAccountService {

    private static final String ISPB_PATTERN = "\\d{8}";

    private final FundsRepository fundsRepository;

    public ParticipantSettlementAccountService(FundsRepository fundsRepository) {
        this.fundsRepository = fundsRepository;
    }

    public void provisionSettlementAccount(String ispb, BigDecimal balance, boolean resetIfExists) {
        validateIspb(ispb);
        if (balance == null || balance.signum() < 0) {
            throw new IllegalArgumentException("Balance must be zero or positive");
        }

        fundsRepository.provisionAccount(ispb, balance, resetIfExists);
    }

    public BigDecimal getSettlementAccountBalance(String ispb) {
        validateIspb(ispb);
        return fundsRepository.getAvailableFunds(ispb);
    }

    private void validateIspb(String ispb) {
        if (ispb == null || !ispb.matches(ISPB_PATTERN)) {
            throw new IllegalArgumentException("ISPB must have exactly 8 digits");
        }
    }
}
