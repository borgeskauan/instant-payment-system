package br.kauan.dict.adapter.output;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.Data;

import java.time.Instant;

@Data
@Entity
public class DictEntity {

    // Later, it is best to create a separate ID field, controlled by the database.
    // And create indexes for the PIX key.
    @Id
    private String pixKey;

    private String keyType;

    private Instant creationDate;
    private Instant keyOwnershipDate;

    private String accountParticipant;
    private String accountBranch;
    private String accountNumber;
    private String accountType;
    private Instant accountOpeningDate;

    private String ownerType;
    private String ownerTaxIdNumber;
    private String ownerName;
}
