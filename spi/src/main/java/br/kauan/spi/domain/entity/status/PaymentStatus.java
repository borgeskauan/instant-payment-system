package br.kauan.spi.domain.entity.status;

// TODO: Adicionar status de reject_no_funds.
public enum PaymentStatus {
    WAITING_ACCEPTANCE,
    ACCEPTED_AND_SETTLED,
    ACCEPTED_AND_SETTLED_FOR_RECEIVER,
    ACCEPTED_AND_SETTLED_FOR_SENDER,
    ACCEPTED_IN_PROCESS,
    REJECTED
}
