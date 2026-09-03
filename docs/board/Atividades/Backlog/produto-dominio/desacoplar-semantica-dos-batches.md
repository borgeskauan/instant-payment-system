# Tornar a semântica independente dos batches

- [ ] Garantir que fronteiras de batch não decidam qual comando estabelece o estado de um pagamento

## Problema

Hoje, duas mensagens divergentes com o mesmo `paymentId` podem produzir resultados diferentes conforme a forma como o Kafka as entrega:

```text
A(id=123, valor=10)
B(id=123, valor=20)

batch [A, B]  → ambas são tratadas como conflitantes
batch [A] [B] → A estabelece o pagamento; B é divergente
```

Há uma variação equivalente para respostas incompatíveis do recebedor. Isso faz um detalhe de transporte — a composição do lote — alterar a semântica do domínio e contradiz a decisão de manter batching somente nas fronteiras de transporte e persistência.

## Objetivo

Tratar mensagens conflitantes dentro do mesmo batch exatamente como elas já seriam tratadas se chegassem em batches separados, preservando sua ordem de processamento.

Para pagamentos, a primeira mensagem válida do batch estabelece o pagamento. As ocorrências seguintes são comparadas com esse estado: conteúdo igual é repetição idempotente; conteúdo diferente é conflito.

Para respostas do recebedor, a primeira transição válida estabelece o resultado. As ocorrências seguintes são idempotentes quando compatíveis e conflitantes quando incompatíveis.

O ajuste deve reutilizar o comportamento já existente entre batches, sem introduzir uma nova regra de ordenação no domínio.

## Critérios de aceite

- `[A, B]` e `[A] [B]` produzem o mesmo pagamento persistido e a mesma classificação de conflito: A estabelece o pagamento e B é divergente;
- respostas `ACCEPTED` e `REJECTED` incompatíveis produzem o mesmo resultado independentemente da composição dos batches;
- repetições idênticas continuam sem reaplicar efeitos financeiros, auditoria ou obrigações de notificação;
- somente mensagens e transições efetivamente aplicadas contribuem para reservas, créditos e liberações;
- testes cobrem mensagens divergentes no mesmo batch e em batches separados;
- `payment-correctness.md` e `engineering-evolution.md` são atualizados depois que a semântica final estiver implementada e verificada.

## Fora de escopo

- alterar o tamanho ou a concorrência dos consumers Kafka como forma de corrigir a semântica;
- remover batching das fronteiras de transporte ou persistência;
- redesenhar a ordenação ou a topologia Kafka;
- alterar a semântica já existente entre batches concorrentes.
