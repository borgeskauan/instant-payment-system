package br.kauan.dict.port.input;

import br.kauan.dict.domain.dtos.PixKeyCreationRequest;
import br.kauan.dict.domain.dtos.PixResponse;

public interface DictUseCase {
    PixResponse buscarChavePix(String chavePix);

    PixResponse createPixKey(PixKeyCreationRequest request);
}
