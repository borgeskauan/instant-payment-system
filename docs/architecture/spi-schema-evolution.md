# Evolução arquitetural do schema do SPI

## Compatibilidade do MVP

O baseline atual é a primeira versão de schema suportada. O MVP assume um banco
PostgreSQL novo e não oferece upgrade executável para instalações criadas pelas
migrations experimentais V1–V18.

O histórico SQL continua disponível no Git até o commit anterior à consolidação.
Este documento preserva as decisões e evidências relevantes sem obrigar o
runtime a reconstruir arquiteturas descartadas a cada banco novo.

## Estado inicial

O primeiro schema espelhava uma modelagem orientada a entidades:

* pagamentos com valores decimais e status textuais amplos;
* contas de liquidação e buckets de saldo separados;
* outbox com lifecycle de publicação, tentativas e timestamps mutáveis;
* auditoria que reproduzia alterações técnicas de status e settlement.

Esse desenho permitiu validar rapidamente o fluxo funcional, mas criou
fragmentação artificial de liquidez, mais writes e índices no PostgreSQL e
vocabulários persistidos mais amplos que o domínio final.

## Saldo e reserva

As migrations V2–V4 introduziram buckets e normalizaram dinheiro para centavos.
Os testes de carga mostraram que buckets adicionavam coordenação sem representar
uma necessidade do negócio. O commit `00a50bf` substituiu-os por uma única row
`participant_balance_entity` por ISPB.

A reserva passou a ser representada atomicamente por:

```text
decremento do saldo do pagador + WAITING_ACCEPTANCE
```

Settlement credita somente o recebedor; rejeição externa libera somente o
pagador. A ordem determinística de locks e os updates agregados permanecem no
adaptador JDBC. A decisão completa está em
[`reservation-based-participant-balance.md`](reservation-based-participant-balance.md).

## Notificações

A outbox inicial possuía estados `PENDING/PUBLISHED`, retry e índices de claim.
Experimentos de performance mostraram write amplification relevante no ciclo de
publicação. Os commits `42f0a2e`, `a63a39f` e `e8bc4f1` conduziram o desenho para
uma outbox transacional mínima:

```text
communication_id, recipient_ispb, payload, created_at
```

Ela existe apenas para fechar atomicamente transação financeira e obrigação de
saída. Kafka é o log durável de delivery e o publisher remove da outbox somente
o lote integral confirmado. O desenho e suas limitações de HA estão em
[`kafka-durable-notification-delivery.md`](kafka-durable-notification-delivery.md).

## Persistência compacta

O commit `af2aff1` converteu status e motivos para enums PostgreSQL, fingerprint
para `BYTEA`, versão para `SMALLINT` e tipos de auditoria para representações
compactas. O fillfactor da tabela de pagamentos foi caracterizado durante a
estabilização e permaneceu em 50 para favorecer suas transições de estado.

No A/B da compactação conjunta, o tempo SQL por row caiu `12,89%` no insert de
pagamentos e `10,19%` no insert de auditoria; o WAL por row caiu respectivamente
`13,09%` e `6,68%`. A cauda melhorou naquela execução, mas throughput rolling e
CPU não apresentaram ganho uniforme. A mudança foi mantida pela redução física
reproduzida e pela representação mais estreita, não como explicação isolada da
capacidade final.

O baseline final também removeu da tabela de pagamentos os campos completos de
pagador e recebedor que o fluxo JDBC nunca consultava. O hot path persiste apenas
identidade, fingerprint, estado, valor e os ISPBs necessários para reserva,
liquidação e rejeição; a notificação transacional continua carregando o payload
externo completo.

O commit `be45b59` removeu índices técnicos de auditoria sem consumidores e a
primary key de `event_id`; o identificador continua obrigatório e gerado para
ordenação/evidência, mas não participa da regra de negócio.

Repetições com e sem esses índices separaram novamente o mecanismo do resultado
sistêmico. Sem os índices técnicos, o insert de auditoria consumiu entre
`38,25%` e `52,43%` menos tempo por row e aproximadamente `46%` menos WAL por
row. A latência end-to-end variou mais entre duas execuções do mesmo lado do que
entre as variantes, portanto não foi atribuída à remoção. Permanecem somente os
índices parciais que protegem fatos de negócio; um índice de consulta só deve
voltar se surgir um consumidor real que justifique seu custo no hot path.

## Auditoria orientada a fatos

O modelo inicial emitia `PAYMENT_CREATED`, `PAYMENT_STATUS_CHANGED` e
`SETTLEMENT_APPLIED`. Ele duplicava detalhes técnicos e dividia uma única
transação de negócio em múltiplas interpretações.

O commit `ed05bb2` consolidou os fatos imutáveis:

* `PAYMENT_RESERVED`;
* `PAYMENT_SETTLED`;
* `PAYMENT_REJECTED`.

Índices parciais garantem uma admissão e um outcome terminal por pagamento.
Shape constraints preservam estado, deltas financeiros e origem do motivo na
mesma row. A definição vigente está em
[`auditoria-transacoes-spi.md`](../board/Atividades/concluidas/auditoria-transacoes-spi.md).

O schema de auditoria anterior existia somente para converter bancos
experimentais. Ele não é recriado no baseline, pois não existe histórico a
preservar em um banco novo.

## Vocabulário final de estados e motivos

O commit `8336cab` separou três conceitos antes misturados:

* `payment_state`: estado operacional interno;
* outcome recebido no PACS.002;
* status externo produzido na notificação.

O schema persiste apenas `WAITING_ACCEPTANCE`, `SETTLED` e `REJECTED`. A causa
interna `INSUFFICIENT_FUNDS` é distinta dos reason codes externos de uma
rejeição do PSP recebedor. Constraints impedem combinações de origens
contraditórias.

## Invariantes do baseline

O baseline preserva diretamente:

* identidade idempotente por `payment_id` e fingerprint;
* saldo não negativo por participante;
* reserva, estado, auditoria e outbox na mesma transação;
* um fato de admissão e um outcome terminal por pagamento;
* separação entre causa interna e reason codes externos;
* outbox mínima e imutável até a confirmação integral do publish;
* fillfactor homologado da tabela de pagamentos.

As antigas migrations permanecem como evidência histórica no Git; o baseline é
o contrato executável e legível do estado final do MVP.
