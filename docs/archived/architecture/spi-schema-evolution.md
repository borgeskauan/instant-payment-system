# Evolução arquitetural do schema do SPI

## Status

Registro histórico. O baseline atual é a primeira versão de banco suportada pelo MVP. Instalações criadas pelas migrations experimentais não possuem caminho de upgrade; ambientes de demonstração e benchmark começam com PostgreSQL novo.

O objetivo deste documento é preservar por que o schema final possui sua forma atual sem obrigar o runtime a reconstruir arquiteturas descartadas.

## De onde o schema partiu

O primeiro modelo espelhava entidades e mecanismos que ainda estavam sendo descobertos:

- pagamentos com valores decimais e vocabulários textuais amplos;
- contas de settlement e buckets de saldo separados;
- outbox com `PENDING/PUBLISHED`, tentativas e timestamps mutáveis;
- auditoria que registrava mudanças técnicas de status;
- tabelas e índices voltados a claim, lease, ACK e redelivery de notificações.

Esse desenho permitiu validar o fluxo funcional cedo, mas carregava no banco decisões que depois deixaram de representar o domínio ou a arquitetura de entrega.

## Decisões que alteraram o modelo

| Área | Modelo experimental | Baseline atual | Motivo |
| --- | --- | --- | --- |
| dinheiro | decimal e múltiplas estruturas de liquidez | centavos inteiros e uma disponibilidade por participante | evitar arredondamento e liquidez artificial |
| reserva | buckets e coordenação no settlement | `WAITING_ACCEPTANCE` + débito da disponibilidade | tornar a reserva parte da transação do pagamento |
| status | strings amplas e motivo misturado ao estado | enums pequenos e motivo separado | reduzir ambiguidade e representação física |
| auditoria | transições técnicas detalhadas | fatos financeiros consolidados | registrar o que aconteceu no negócio |
| outbox | lifecycle de publicação mutável | notificação imutável mínima | manter apenas a ponte transacional para Kafka |
| delivery | estado persistido de cada entrega | Kafka + cursor do PSP | retirar tracking operacional do PostgreSQL |

## Saldo e reserva

O commit `00a50bf` substituiu buckets por `participant_balance_entity`, uma row por ISPB. A reserva passou a ser:

```text
decremento da disponibilidade do pagador
+
payment.status = WAITING_ACCEPTANCE
```

Aceite credita somente o recebedor; rejeição externa devolve somente a reserva do pagador. A [decisão de saldo e reserva](reservation-based-participant-balance.md) registra as invariantes e a concorrência.

## Outbox e entrega

Os estados de publicação e os índices de claim foram removidos em etapas. A outbox final contém uma confirmação imutável criada na mesma transação financeira:

```text
communication_id
recipient_ispb
payload
created_at
```

Depois da confirmação no Kafka, a row pode ser apagada. Histórico de entrega e progresso não ficam no schema do SPI. A [decisão de Kafka durável](kafka-durable-notification-delivery.md) explica essa fronteira.

## Representação física

O commit `af2aff1` compactou representações que já estavam semanticamente estabilizadas:

- status e motivos tornaram-se enums PostgreSQL;
- fingerprint tornou-se `BYTEA`;
- versão tornou-se `SMALLINT`;
- dinheiro permaneceu em `BIGINT` de centavos;
- payload da outbox permaneceu pré-serializado e imutável.

No microbenchmark conjunto, tempo SQL por row caiu `12,89%` no insert de pagamentos e `10,19%` no insert de auditoria; WAL por row caiu `13,09%` e `6,68%`, respectivamente. O resultado end-to-end não foi uniforme, então a compactação permaneceu por reduzir custo físico mensurável, não como explicação isolada da qualificação.

O fillfactor da tabela de pagamentos ficou em 50. Isso levou updates HOT de `22,86%` para `100%`, ao custo de aproximadamente `46,98%` a mais em heap e índices e inserts mais caros. A decisão favorece as transições frequentes da row e aceita o custo de espaço; não representa um ótimo universal.

## Auditoria orientada a fatos

A auditoria deixou de reproduzir cada alteração técnica e passou a registrar os fatos que permitem explicar a movimentação financeira:

| Fato | Efeito registrado |
| --- | --- |
| `PAYMENT_RESERVED` | débito da disponibilidade do pagador |
| `PAYMENT_SETTLED` | crédito do recebedor |
| `PAYMENT_REJECTED` | ausência de efeito na insuficiência inicial ou devolução de reserva existente |

Índices técnicos sem consumidor foram removidos. No microbenchmark, isso reduziu o insert de auditoria entre `38,25%` e `52,43%` por row e cerca de `46%` de WAL por row. A escolha também reduz ambiguidade: auditoria descreve fatos aplicados, não tentativas ou replays sem efeito.

## Por que consolidar em um baseline

Manter todas as migrations experimentais como caminho executável teria três custos:

1. obrigaria um banco novo a reconstruir estruturas imediatamente descartadas;
2. sugeriria compatibilidade de upgrade nunca qualificada;
3. manteria código e testes dedicados a estados que o produto final não suporta.

O baseline novo reduz essa complexidade permanente. A evolução continua verificável pelo Git, por este registro e pelos documentos de decisão relacionados.

## Consequências

- O MVP exige banco novo; não oferece migração de dados legados.
- Alterar enums PostgreSQL exige migration explícita.
- Fillfactor 50 troca espaço por updates HOT e deve ser reavaliado se o padrão de escrita mudar.
- O schema não contém estado suficiente para reconstruir cada arquitetura histórica — deliberadamente.
- A autoridade vigente continua sendo o código e o baseline atual, não os nomes ou exemplos preservados no arquivo.
