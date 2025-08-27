package br.kauan.spi.adapter.input;

import br.kauan.spi.dtos.pacs.pacs002.FIToFIPaymentStatusReport;
import br.kauan.spi.dtos.pacs.pacs008.FIToFICustomerCreditTransfer;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HelloController {

    @GetMapping("/report")
    public FIToFIPaymentStatusReport hello() {
        return new FIToFIPaymentStatusReport();
    }

    @GetMapping("/transfer")
    public FIToFICustomerCreditTransfer transfer() {
        return new FIToFICustomerCreditTransfer();
    }
}
