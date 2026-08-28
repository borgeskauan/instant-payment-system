package br.kauan.dict.adapter.output;

import br.kauan.dict.domain.dtos.Account;
import br.kauan.dict.domain.dtos.Owner;
import br.kauan.dict.domain.dtos.PixKeyCreationInternalRequest;
import br.kauan.dict.domain.dtos.PixResponse;
import org.springframework.stereotype.Component;

@Component
public class PixResponseMapper {

    public DictEntity toEntity(PixKeyCreationInternalRequest internalPixRequest) {
        if (internalPixRequest == null) {
            return null;
        }

        DictEntity dictEntity = new DictEntity();

        // Direct fields
        dictEntity.setPixKey(internalPixRequest.getKey());
        dictEntity.setKeyType(internalPixRequest.getKeyType().name());
        dictEntity.setCreationDate(internalPixRequest.getCreationDate());
        dictEntity.setKeyOwnershipDate(internalPixRequest.getKeyOwnershipDate());

        // Account fields
        if (internalPixRequest.getAccount() != null) {
            dictEntity.setAccountParticipant(internalPixRequest.getAccount().getParticipant());
            dictEntity.setAccountBranch(internalPixRequest.getAccount().getBranch());
            dictEntity.setAccountNumber(internalPixRequest.getAccount().getNumber());
            dictEntity.setAccountType(internalPixRequest.getAccount().getType());
            dictEntity.setAccountOpeningDate(internalPixRequest.getAccount().getOpeningDate());
        }

        // Owner fields
        if (internalPixRequest.getOwner() != null) {
            dictEntity.setOwnerType(internalPixRequest.getOwner().getType());
            dictEntity.setOwnerTaxIdNumber(internalPixRequest.getOwner().getTaxIdNumber());
            dictEntity.setOwnerName(internalPixRequest.getOwner().getName());
        }

        return dictEntity;
    }

    public PixResponse fromEntity(DictEntity dictEntity) {
        if (dictEntity == null) {
            return null;
        }

        return PixResponse.builder()
                .key(dictEntity.getPixKey())
                .keyType(dictEntity.getKeyType())
                .creationDate(dictEntity.getCreationDate())
                .keyOwnershipDate(dictEntity.getKeyOwnershipDate())
                .account(mapToAccount(dictEntity))
                .owner(mapToOwner(dictEntity))
                .build();
    }

    // Helper method to create Account object
    private Account mapToAccount(DictEntity dictEntity) {
        if (dictEntity.getAccountParticipant() == null &&
            dictEntity.getAccountBranch() == null &&
            dictEntity.getAccountNumber() == null &&
            dictEntity.getAccountType() == null &&
            dictEntity.getAccountOpeningDate() == null) {
            return null;
        }

        return Account.builder()
                .participant(dictEntity.getAccountParticipant())
                .branch(dictEntity.getAccountBranch())
                .number(dictEntity.getAccountNumber())
                .type(dictEntity.getAccountType())
                .openingDate(dictEntity.getAccountOpeningDate())
                .build();
    }

    // Helper method to create Owner object
    private Owner mapToOwner(DictEntity dictEntity) {
        if (dictEntity.getOwnerType() == null &&
            dictEntity.getOwnerTaxIdNumber() == null &&
            dictEntity.getOwnerName() == null) {
            return null;
        }

        return Owner.builder()
                .type(dictEntity.getOwnerType())
                .taxIdNumber(dictEntity.getOwnerTaxIdNumber())
                .name(dictEntity.getOwnerName())
                .build();
    }
}