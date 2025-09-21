package br.kauan.paymentserviceprovider.adapter.output;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PixKeyJpaClient extends JpaRepository<PixKeyEntity, String> {
    List<PixKeyEntity> findAllByCustomerId(String customerId);
}
