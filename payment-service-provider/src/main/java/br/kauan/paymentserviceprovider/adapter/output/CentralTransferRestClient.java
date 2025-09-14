package br.kauan.paymentserviceprovider.adapter.output;

import br.kauan.paymentserviceprovider.adapter.output.pacs.pacs008.FIToFICustomerCreditTransfer;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "central-transfer", url = "${external.central-transfer.url}")
public interface CentralTransferRestClient {

    @PostMapping("/{ispb}/transfer")
    void requestTransfer(@PathVariable String ispb, @RequestBody FIToFICustomerCreditTransfer transferRequest);

    @GetMapping("/{ispb}/messages")
    SpiNotification getMessages(@PathVariable String ispb);
}
