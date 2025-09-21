package br.kauan.paymentserviceprovider.adapter.output;

import br.kauan.paymentserviceprovider.port.output.PixKeyRepository;
import org.springframework.stereotype.Repository;

@Repository
public class PixKeyJpaAdapter implements PixKeyRepository {

    private final PixKeyJpaClient pixKeyJpaClient;

    public PixKeyJpaAdapter(PixKeyJpaClient pixKeyJpaClient) {
        this.pixKeyJpaClient = pixKeyJpaClient;
    }

    @Override
    public void save(String key, String customerId) {
        var entity = PixKeyEntity.builder()
                .pixKey(key)
                .customerId(customerId)
                .build();

        pixKeyJpaClient.save(entity);
    }
}
