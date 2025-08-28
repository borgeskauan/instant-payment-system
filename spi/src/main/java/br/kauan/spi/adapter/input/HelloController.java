package br.kauan.spi.adapter.input;

import br.kauan.spi.dtos.pacs.pacs002.FIToFIPaymentStatusReport;
import br.kauan.spi.dtos.pacs.pacs008.FIToFICustomerCreditTransfer;
import org.springframework.web.bind.annotation.*;

@RestController
public class HelloController {

    @PostMapping("/{ispb}/transfer")
    public void transfer(@PathVariable String ispb, @RequestBody FIToFICustomerCreditTransfer request) {
        System.out.println(request);
    }

    @PostMapping("/{ispb}/transfer/status")
    public void transferStatus(@PathVariable String ispb, @RequestBody FIToFIPaymentStatusReport statusReport) {
        System.out.println(statusReport);
    }
}
