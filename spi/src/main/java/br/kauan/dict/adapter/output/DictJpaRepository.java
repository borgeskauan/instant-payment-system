package br.kauan.dict.adapter.output;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DictJpaRepository extends JpaRepository<DictEntity, String> {
}
