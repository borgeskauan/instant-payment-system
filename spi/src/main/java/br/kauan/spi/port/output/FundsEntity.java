package br.kauan.spi.port.output;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Entity
public class FundsEntity {

    @Id
    private String bankCode;
    private BigDecimal balance;
}
