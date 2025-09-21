package br.kauan.paymentserviceprovider.adapter.output;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerEntity {

    @Id
    private String id;

    private String name;
    private String taxId;

    private String accountNumber;
    private String accountAgency;
    private String accountType;
}
