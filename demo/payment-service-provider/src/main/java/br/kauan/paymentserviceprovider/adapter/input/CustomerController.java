package br.kauan.paymentserviceprovider.adapter.input;

import br.kauan.paymentserviceprovider.domain.dto.CustomerSnapshot;
import br.kauan.paymentserviceprovider.domain.dto.OpenCustomerRequest;
import br.kauan.paymentserviceprovider.domain.dto.PixKeyCreationRequest;
import br.kauan.paymentserviceprovider.domain.dto.PaymentSummary;
import br.kauan.paymentserviceprovider.domain.entity.customer.PixKey;
import br.kauan.paymentserviceprovider.domain.services.cts.PaymentHistoryService;
import br.kauan.paymentserviceprovider.domain.services.customer.CustomerService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@CrossOrigin(origins = "http://localhost:4200")
public class CustomerController {

    private final CustomerService customerService;
    private final PaymentHistoryService paymentHistoryService;

    public CustomerController(CustomerService customerService, PaymentHistoryService paymentHistoryService) {
        this.customerService = customerService;
        this.paymentHistoryService = paymentHistoryService;
    }

    @PostMapping("/customers")
    public CustomerSnapshot openCustomer(@RequestBody OpenCustomerRequest request) {
        return customerService.openCustomer(request);
    }

    @PostMapping("/customers/{customerId}/pix-keys")
    public void createPixKey(@PathVariable String customerId, @RequestBody PixKeyCreationRequest request) {
        customerService.createPixKey(customerId, request.pixKey());
    }

    @GetMapping("/customers/{customerId}/pix-keys")
    public List<PixKey> getPixKeys(@PathVariable String customerId) {
        return customerService.getAllPixKeys(customerId);
    }

    @GetMapping("/customers/{customerId}/payments")
    public List<PaymentSummary> getPayments(@PathVariable String customerId) {
        return paymentHistoryService.findByCustomerId(customerId);
    }
}
