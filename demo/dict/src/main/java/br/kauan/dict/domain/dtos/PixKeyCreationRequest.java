package br.kauan.dict.domain.dtos;

import lombok.Data;

@Data
public class PixKeyCreationRequest {
    private String key;
    private PixKeyType keyType;
    private Account account;
    private Owner owner;
}
