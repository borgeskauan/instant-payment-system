package br.kauan.spi.adapter.input;

import br.kauan.spi.domain.entity.status.StatusReport;
import br.kauan.spi.domain.entity.transfer.PaymentBatch;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HelloController {

    @PostMapping("/{ispb}/transfer")
    public void transfer(@PathVariable String ispb, @RequestBody PaymentBatch request) {
        System.out.println(request);
    }

    @PostMapping("/{ispb}/transfer/status")
    public void transferStatus(@PathVariable String ispb, @RequestBody StatusReport statusReport) {
        System.out.println(statusReport);
    }
}
