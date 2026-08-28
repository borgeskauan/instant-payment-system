package br.kauan.dict.port.output;

import br.kauan.dict.domain.dtos.PixKeyCreationInternalRequest;
import br.kauan.dict.domain.dtos.PixResponse;

import java.util.Optional;

public interface DictRepository {
    PixResponse createPixKey(PixKeyCreationInternalRequest request);

    Optional<PixResponse> buscarChavePix(String chavePix);
}
