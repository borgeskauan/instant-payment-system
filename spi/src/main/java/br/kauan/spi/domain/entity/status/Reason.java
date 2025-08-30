package br.kauan.spi.domain.entity.status;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class Reason {
    private String code; // "AB03"
    private String description; // "Invalid Creditor Account Number"
}
