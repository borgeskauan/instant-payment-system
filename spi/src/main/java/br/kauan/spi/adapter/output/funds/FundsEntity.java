package br.kauan.spi.adapter.output.funds;


import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;

@Data
@Builder
@AllArgsConstructor
@Table("funds_entity")
public class FundsEntity {

    @Id
    private String bankCode;
    private BigDecimal balance;
}
