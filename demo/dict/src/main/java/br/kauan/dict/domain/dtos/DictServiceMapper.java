package br.kauan.dict.domain.dtos;

import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface DictServiceMapper {
    PixKeyCreationInternalRequest toInternalRequest(PixKeyCreationRequest request);
}
