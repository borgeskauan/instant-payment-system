package br.kauan.paymentserviceprovider.adapter.input;

import br.kauan.paymentserviceprovider.domain.dto.CustomerLoginRequest;
import br.kauan.paymentserviceprovider.domain.dto.CustomerLoginResponse;
import br.kauan.paymentserviceprovider.domain.dto.PixKeyCreationRequest;
import br.kauan.paymentserviceprovider.domain.entity.customer.PixKey;
import br.kauan.paymentserviceprovider.domain.services.customer.CustomerService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
public class CustomerController {

    private final CustomerService customerService;

    public CustomerController(CustomerService customerService) {
        this.customerService = customerService;
    }

    @PostMapping("/customers")
    public CustomerLoginResponse loginCustomer(@RequestBody CustomerLoginRequest request) {
        return customerService.loginCustomer(request);
    }

    @PostMapping("/customers/{customerId}/pix-keys")
    public void createPixKey(@PathVariable String customerId, @RequestBody PixKeyCreationRequest request) {
        request.setCustomerId(customerId);

        customerService.createPixKey(request);
    }

    @GetMapping("/customers/{customerId}/pix-keys")
    public List<PixKey> getPixKeys(@PathVariable String customerId) {
        return customerService.getAllPixKeys(customerId);
    }
}
