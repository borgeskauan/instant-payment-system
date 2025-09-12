package br.kauan.paymentserviceprovider.port.output;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.Data;

@Data
@Entity
public class CustomerEntity {

    @Id
    private String id;

    private String name;
    private String taxId;

    private String accountNumber;
    private String accountAgency;
    private String accountType;
}
