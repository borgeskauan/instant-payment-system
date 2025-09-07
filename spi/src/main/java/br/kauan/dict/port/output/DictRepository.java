package br.kauan.dict.port.output;

import br.kauan.dict.domain.dtos.PixKeyCreationInternalRequest;
import br.kauan.dict.domain.dtos.PixResponse;

public interface DictRepository {
    PixResponse createPixKey(PixKeyCreationInternalRequest request);

    PixResponse buscarChavePix(String chavePix);
}
