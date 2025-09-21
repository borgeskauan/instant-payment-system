package br.kauan.paymentserviceprovider.adapter.output;

import br.kauan.paymentserviceprovider.domain.entity.PixKey;
import br.kauan.paymentserviceprovider.port.output.PixKeyRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

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

    @Override
    public List<PixKey> findAllByCustomerId(String customerId) {
        var entities = pixKeyJpaClient.findAllByCustomerId(customerId);
        return entities.stream()
                .map(e -> PixKey.builder()
                        .pixKey(e.getPixKey())
                        .build())
                .toList();
    }
}
