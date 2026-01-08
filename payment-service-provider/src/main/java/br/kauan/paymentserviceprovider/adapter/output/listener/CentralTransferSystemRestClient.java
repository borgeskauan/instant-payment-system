package br.kauan.paymentserviceprovider.adapter.output.listener;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "central-transfer", url = "${external.central-transfer.url}")
public interface CentralTransferSystemRestClient {

    @PostMapping(value = "/{ispb}/transfer", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    void requestTransfer(@PathVariable String ispb, @RequestBody byte[] transferRequest);

    @PostMapping(value = "/{ispb}/transfer/status", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    void sendTransferStatus(@PathVariable String ispb, @RequestBody byte[] statusReport);

    @GetMapping("/{ispb}/messages")
    SpiNotification getMessages(@PathVariable String ispb);
}
