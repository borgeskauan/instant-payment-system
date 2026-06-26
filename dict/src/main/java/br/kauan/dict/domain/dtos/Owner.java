package br.kauan.dict.domain.dtos;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class Owner {
    private String type;
    private String taxIdNumber;
    private String name;
}
