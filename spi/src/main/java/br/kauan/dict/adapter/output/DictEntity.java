package br.kauan.dict.adapter.output;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

@Data
@Table("dict_entity")
public class DictEntity {

    @Id
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

    public DictEntity() {
        this.id = UUID.randomUUID().toString();
    }
}
