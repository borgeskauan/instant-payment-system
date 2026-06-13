package br.kauan.spi.adapter.input.admin;

import br.kauan.spi.domain.services.ParticipantSettlementAccountService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/funds")
public class FundsAdminController {

    private final ParticipantSettlementAccountService settlementAccountService;

    public FundsAdminController(ParticipantSettlementAccountService settlementAccountService) {
        this.settlementAccountService = settlementAccountService;
    }

    @PutMapping("/{ispb}")
    public ResponseEntity<Void> provisionSettlementAccount(
            @PathVariable String ispb,
            @RequestBody ProvisionSettlementAccountRequest request
    ) {
        settlementAccountService.provisionSettlementAccount(ispb, request.balance(), request.resetIfExists());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{ispb}")
    public ResponseEntity<SettlementAccountBalanceResponse> getSettlementAccountBalance(@PathVariable String ispb) {
        var balance = settlementAccountService.getSettlementAccountBalance(ispb);
        return ResponseEntity.ok(new SettlementAccountBalanceResponse(ispb, balance));
    }
}
