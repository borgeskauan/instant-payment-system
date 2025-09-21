package br.kauan.paymentserviceprovider.adapter.input;

import br.kauan.paymentserviceprovider.domain.dto.CustomerLoginRequest;
import br.kauan.paymentserviceprovider.domain.dto.PixKeyCreationRequest;
import br.kauan.paymentserviceprovider.domain.entity.Customer;
import br.kauan.paymentserviceprovider.domain.services.CustomerService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CustomerController {

    private final CustomerService customerService;

    public CustomerController(CustomerService customerService) {
        this.customerService = customerService;
    }

    @PostMapping("/customers")
    public Customer loginCustomer(@RequestBody CustomerLoginRequest request) {
        return customerService.loginCustomer(request);
    }

    @PostMapping("/customers/pix-keys")
    public void createPixKey(@RequestBody PixKeyCreationRequest request) {
        customerService.createPixKey(request);
    }
}
