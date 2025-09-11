package br.kauan.spi.port.output;

import org.springframework.data.jpa.repository.JpaRepository;

public interface FundsJpaClient extends JpaRepository<FundsEntity, String> {
}
