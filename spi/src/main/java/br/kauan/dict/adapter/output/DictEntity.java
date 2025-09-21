package br.kauan.dict.adapter.output;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.Data;

import java.time.Instant;

@Data
@Entity
public class DictEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

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
