package br.kauan.dict.domain.dtos;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class PixResponse {
    private String key;
    private String keyType;
    private Account account;
    private Owner owner;
    private Instant creationDate;
    private Instant keyOwnershipDate;
}