package br.kauan.paymentserviceprovider.port.output;

import br.kauan.paymentserviceprovider.domain.entity.PixKey;

import java.util.List;

public interface PixKeyRepository {
    void save(String key, String customerId);

    List<PixKey> findAllByCustomerId(String customerId);
}
