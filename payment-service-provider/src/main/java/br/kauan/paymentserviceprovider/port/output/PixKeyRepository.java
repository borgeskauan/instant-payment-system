package br.kauan.paymentserviceprovider.port.output;

public interface PixKeyRepository {
    void save(String key, String customerId);
}
