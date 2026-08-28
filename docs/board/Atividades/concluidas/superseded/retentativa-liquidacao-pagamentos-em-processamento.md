# Retentativa de liquidação de pagamentos em processamento

- [x] Superseded pela reserva de saldo no ingresso do pagamento

## Por que foi superseded

Esta task pertencia à arquitetura anterior, na qual a liquidez do pagador só era verificada durante o processamento do PACS.002 aceito. Quando não havia saldo no bucket escolhido, o pagamento permanecia em `ACCEPTED_IN_PROCESS` sem um responsável por retomar a liquidação.

A arquitetura vigente reserva o saldo disponível do pagador durante a admissão do PACS.008:

```text
saldo suficiente   -> reserva -> WAITING_ACCEPTANCE
saldo insuficiente -> REJECTED / AM04
```

Depois da reserva, um PACS.002 aceito credita somente o recebedor. A liquidação não pode mais ficar aguardando liquidez do pagador, e o domínio persistido possui apenas `WAITING_ACCEPTANCE`, `SETTLED` e `REJECTED`.

Por isso não foram implementados worker de polling, retry de liquidação, claim, lease ou `next_attempt_at`. Esses mecanismos resolveriam um estado que deixou de existir.

O desenho vigente está documentado em [Saldo por participante com reserva implícita no pagamento](../../../../architecture/reservation-based-participant-balance.md).
