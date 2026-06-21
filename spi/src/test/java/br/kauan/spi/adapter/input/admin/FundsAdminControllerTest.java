package br.kauan.spi.adapter.input.admin;

import br.kauan.spi.domain.services.ParticipantSettlementAccountService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FundsAdminControllerTest {

    @Test
    void provisionSettlementAccountDelegatesToService() {
        ParticipantSettlementAccountService service = mock(ParticipantSettlementAccountService.class);
        FundsAdminController controller = new FundsAdminController(service);

        controller.provisionSettlementAccount(
                "10000001",
                new ProvisionSettlementAccountRequest(BigDecimal.valueOf(1_000_000_000), true)
        );

        verify(service).provisionSettlementAccount("10000001", BigDecimal.valueOf(1_000_000_000), true);
    }

    @Test
    void getSettlementAccountBalanceReturnsIspbAndBalance() {
        ParticipantSettlementAccountService service = mock(ParticipantSettlementAccountService.class);
        FundsAdminController controller = new FundsAdminController(service);
        when(service.getSettlementAccountBalance("10000001")).thenReturn(BigDecimal.TEN);

        var response = controller.getSettlementAccountBalance("10000001");

        assertEquals(200, response.getStatusCode().value());
        assertEquals(new SettlementAccountBalanceResponse("10000001", BigDecimal.TEN), response.getBody());
        verify(service).getSettlementAccountBalance("10000001");
    }
}
