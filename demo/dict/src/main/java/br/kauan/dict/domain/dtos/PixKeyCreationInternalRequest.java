package br.kauan.dict.domain.dtos;

import lombok.Builder;
import lombok.Data;
import lombok.With;

import java.time.Instant;

@Data
@Builder
@With
public class PixKeyCreationInternalRequest {
    private String key;
    private PixKeyType keyType;
    private Account account;
    private Owner owner;

    private Instant creationDate;
    private Instant keyOwnershipDate;
}
