package br.kauan.dict.adapter.output;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

public interface DictJpaRepository extends ReactiveCrudRepository<DictEntity, String> {
    Mono<DictEntity> findByPixKey(String pixKey);
}
