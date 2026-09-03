package br.kauan.paymentserviceprovider.adapter.input;

import br.kauan.paymentserviceprovider.domain.dto.PaymentSummary;
import br.kauan.paymentserviceprovider.domain.entity.transfer.PaymentDirection;
import br.kauan.paymentserviceprovider.domain.entity.transfer.PaymentLifecycleStatus;
import br.kauan.paymentserviceprovider.domain.services.cts.PaymentHistoryService;
import br.kauan.paymentserviceprovider.domain.services.customer.CustomerService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CustomerControllerTest {

    @Test
    void listsPaymentsUsingTheCustomerFacingContract() throws Exception {
        CustomerService customerService = mock(CustomerService.class);
        PaymentHistoryService historyService = mock(PaymentHistoryService.class);
        when(historyService.findByCustomerId("customer-1")).thenReturn(List.of(new PaymentSummary(
                "E2E-1",
                PaymentDirection.OUTGOING,
                new PaymentSummary.Counterparty("Bob", "bob@example.com"),
                new BigDecimal("10.00"),
                "BRL",
                PaymentLifecycleStatus.SETTLED,
                Instant.parse("2026-08-28T20:00:00Z")
        )));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                new CustomerController(customerService, historyService)
        ).build();

        mvc.perform(get("/customers/customer-1/payments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].paymentId").value("E2E-1"))
                .andExpect(jsonPath("$[0].direction").value("OUTGOING"))
                .andExpect(jsonPath("$[0].counterparty.name").value("Bob"))
                .andExpect(jsonPath("$[0].status").value("SETTLED"));
    }
}
