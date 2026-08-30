# Substituir buckets por reserva no saldo do participante

- [x] Migrar o SPI para saldo único por participante com reserva no PACS.008

## Resultado

O SPI deixou de fragmentar a liquidez em buckets e passou a manter uma única row de saldo disponível por participante.

O fluxo vigente é:

```text
PACS.008           -> reserva no pagador ou rejeita com RJCT/AM04
PACS.002 aceito    -> credita somente o recebedor
PACS.002 rejeitado -> libera a reserva para o pagador
```

`WAITING_ACCEPTANCE` representa que o valor já foi removido do saldo disponível do pagador. Não existem tabela, status ou lifecycle próprios de reserva.

A implementação preserva atomicidade entre saldo, estado do pagamento, auditoria e obrigação de notificação, além de idempotência sob replay. O schema e os adapters vigentes não possuem `funds_bucket_entity`, `bucket_id` ou hashing de liquidez.

O desenho vigente e suas invariantes estão consolidados em [System design](../../../design.md). A evolução experimental permanece no histórico Git.
