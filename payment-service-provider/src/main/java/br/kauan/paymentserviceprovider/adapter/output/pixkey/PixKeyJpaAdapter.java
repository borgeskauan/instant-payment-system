package br.kauan.paymentserviceprovider.adapter.output.pixkey;

import br.kauan.paymentserviceprovider.domain.entity.customer.PixKey;
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
    public void save(PixKey key) {
        var entity = PixKeyEntity.builder()
                .pixKey(key.getPixKey())
                .customerId(key.getCustomerId())
                .type(key.getType())
                .build();

        pixKeyJpaClient.save(entity);
    }

    @Override
    public List<PixKey> findAllByCustomerId(String customerId) {
        var entities = pixKeyJpaClient.findAllByCustomerId(customerId);
        return entities.stream()
                .map(e -> PixKey.builder()
                        .pixKey(e.getPixKey())
                        .type(e.getType())
                        .build())
                .toList();
    }
}
