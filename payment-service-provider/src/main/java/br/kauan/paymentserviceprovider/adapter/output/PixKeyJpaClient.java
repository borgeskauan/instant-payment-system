package br.kauan.paymentserviceprovider.adapter.output;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PixKeyJpaClient extends JpaRepository<PixKeyEntity, String> {
}
