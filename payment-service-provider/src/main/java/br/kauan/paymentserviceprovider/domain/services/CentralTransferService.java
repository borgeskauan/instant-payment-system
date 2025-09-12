package br.kauan.paymentserviceprovider.domain.services;

import br.kauan.paymentserviceprovider.domain.entity.Party;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
public class CentralTransferService {

    public void requestTransfer(Party sender, Party receiver, BigDecimal amount) {
        // Logic to interact with the central transfer system (e.g., BACEN's PIX system)
        // This is a placeholder for the actual implementation.
        System.out.println("Requesting transfer of " + amount + " from " + sender.getName() + " to " + receiver.getName());
    }
}
