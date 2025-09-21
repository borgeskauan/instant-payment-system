package br.kauan.paymentserviceprovider.adapter.output.dict;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

@FeignClient(name = "dictClient", url = "${external.dict.url}")
public interface DictClient {

    @GetMapping("/keys/{chavePix}")
    DictResponse getPartyDetails(@PathVariable String chavePix);

    @PostMapping("/keys")
    DictResponse createPixKey(DictPixKeyCreationRequest request);
}
