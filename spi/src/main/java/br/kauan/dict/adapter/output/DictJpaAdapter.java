package br.kauan.dict.adapter.output;

import br.kauan.dict.domain.dtos.PixKeyCreationInternalRequest;
import br.kauan.dict.domain.dtos.PixResponse;
import br.kauan.dict.port.output.DictRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class DictJpaAdapter implements DictRepository {

    private final DictJpaRepository dictJpaRepository;
    private final PixResponseMapper pixResponseMapper;

    public DictJpaAdapter(DictJpaRepository dictJpaRepository, PixResponseMapper pixResponseMapper) {
        this.dictJpaRepository = dictJpaRepository;
        this.pixResponseMapper = pixResponseMapper;
    }

    @Override
    public PixResponse createPixKey(PixKeyCreationInternalRequest request) {
        var entity = pixResponseMapper.toEntity(request);
        var savedEntity = dictJpaRepository.save(entity);
        return pixResponseMapper.fromEntity(savedEntity);
    }

    @Override
    public Optional<PixResponse> buscarChavePix(String chavePix) {
        var entity = dictJpaRepository.findById(chavePix);
        return entity.map(pixResponseMapper::fromEntity);
    }
}
