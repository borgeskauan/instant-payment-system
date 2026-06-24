package br.kauan.spi.port.output;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.Data;

@Data
@Entity
public class FundsEntity {

    @Id
    private String bankCode;
    private Long balanceCents;
}
