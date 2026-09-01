# Saldo por participante com reserva implícita

## Status

Decisão vigente no MVP. O [design canônico](../../design.md) explica seu comportamento atual; este registro preserva o problema, as alternativas e as consequências que levaram ao modelo.

## Problema

O primeiro schema distribuía o saldo de cada participante em 16 buckets selecionados pelo hash do `payment_id`. A intenção era diminuir contenção, mas o mecanismo introduzia uma forma de liquidez que não existia no domínio.

Um participante podia ter dinheiro suficiente na soma das rows e ainda rejeitar um pagamento porque o bucket escolhido não tinha saldo. O settlement também precisava coordenar bucket do pagador e do recebedor, e o schema carregava hash, identificador de bucket e lógica de soma e rebalanceamento.

O problema de negócio era menor:

> Dinheiro comprometido por um pagamento ainda não concluído não pode continuar disponível para outro pagamento.

## Alternativas consideradas

| Alternativa | Consequência |
| --- | --- |
| manter buckets | reduz conflitos físicos em algumas distribuições, mas preserva liquidez artificial e coordenação adicional |
| criar saldo disponível + saldo reservado | torna a reserva explícita, mas duplica estado que precisa permanecer consistente com cada pagamento |
| criar uma tabela de reservas | representa cada obrigação separadamente, ao custo de mais lifecycle, writes e regras de reconciliação |
| uma disponibilidade por participante + reserva no estado do pagamento | mantém uma única autoridade de saldo e usa a própria transação do pagamento para representar o compromisso |

## Decisão

Cada participante possui uma única disponibilidade no PostgreSQL. A reserva ocorre durante a admissão do `pacs.008`:

```text
pacs.008 admitido           → reduz disponibilidade do pagador
pacs.002 aceito             → credita o recebedor
pacs.002 rejeitado          → devolve a reserva ao pagador
```

Não existem `reserved_balance`, tabela de reservas ou buckets. A equivalência durável é:

```text
payment.status = WAITING_ACCEPTANCE
⇔
payment.amount_cents já saiu da disponibilidade do pagador
```

Criação do pagamento, reserva, auditoria e obrigação de notificar compartilham a mesma transação. No aceite, o pagador não é debitado novamente; na rejeição, somente uma reserva previamente adquirida pode ser devolvida.

Insuficiência de saldo no ingresso produz `REJECTED / INSUFFICIENT_FUNDS`, sem reserva e sem solicitação de aceite ao recebedor.

## Concorrência e idempotência

O PostgreSQL serializa mutações que disputam a mesma row de participante. Participantes distintos continuam independentes, e locks são adquiridos em ordem determinística quando uma transação toca mais de uma row.

Essa serialização acontece entre batches concorrentes, não entre os pagamentos que já chegaram no mesmo batch. A transação bloqueia cada participante envolvido uma vez, avalia em memória os pagamentos daquele pagador, agrega o delta e aplica uma mutação física por participante. Fazer os pagamentos do próprio batch disputarem buckets não criava paralelismo útil: todos ainda pertenciam ao mesmo commit, mas pagavam o custo de hash, agrupamento, coordenação de locks e updates por bucket.

Dentro do lote de um pagador, os pagamentos são avaliados na ordem original. Uma rejeição não interrompe os pagamentos seguintes:

```text
saldo disponível = 100

80 → reserva; restam 20
50 → rejeita; restam 20
10 → reserva; restam 10
```

Somente pagamentos que a transação corrente estabeleceu como novos podem contribuir para o débito agregado. Da mesma forma, crédito ou devolução só podem ser derivados das rows cuja transição desde `WAITING_ACCEPTANCE` foi efetivamente adquirida.

Essa regra impede que duas cópias concorrentes do mesmo `pacs.008` reservem duas vezes ou que duas respostas equivalentes creditem ou devolvam o mesmo valor novamente.

## Por que esta alternativa permaneceu

O modelo removeu uma abstração sintética, reduziu schema e coordenação e aproximou a persistência da invariante financeira. A agregação intrabatch reduziu o trabalho por transação, enquanto o lock entre batches preservou a ordem necessária quando duas operações disputavam o mesmo dinheiro.

A mudança foi incorporada no commit `00a50bf` e sobreviveu à estabilização e às duas qualificações finais. O resultado de performance não prova que uma row por participante seja universalmente superior; demonstra que essa solução foi suficiente para a topologia e a carga qualificadas sem reintroduzir liquidez artificial.

## Consequências e limites

- Um participante muito concentrado pode tornar sua row um ponto de serialização.
- Escala multi-instância e contenção entre réplicas não foram qualificadas.
- `WAITING_ACCEPTANCE` não expira automaticamente. Um recebedor que nunca responde pode manter disponibilidade reservada indefinidamente.
- Onboarding inconsistente — por exemplo, ausência da row de saldo esperada — continua sendo falha operacional, não criação automática de dinheiro.

As decisões físicas posteriores — tipos compactos, fillfactor e baseline do banco — estão registradas na [evolução do schema](spi-schema-evolution.md).
