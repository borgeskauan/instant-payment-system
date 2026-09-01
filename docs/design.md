# System Design

O README descreve o fluxo do ponto de vista de quem faz um pagamento. Aqui o foco é o comportamento interno do sistema quando entram em cena duplicidade, concorrência e falhas.

As duas garantias principais são:

1. o estado do pagamento e a movimentação financeira precisam ser atualizados de forma consistente;
2. depois que uma confirmação é criada, ela precisa continuar recuperável até ser entregue às instituições.

O PostgreSQL é a autoridade sobre pagamentos e saldos. A outbox faz a ponte entre essa transação e o Kafka, evitando uma janela em que o pagamento seja concluído mas sua confirmação seja perdida. Depois da publicação, o Kafka mantém o histórico disponível para recuperação, e cada instituição controla o próprio progresso de consumo.

| Responsabilidade                          | Autoridade               |
| ----------------------------------------- | ------------------------ |
| Estado do pagamento e saldos              | PostgreSQL               |
| Obrigação de publicar uma confirmação     | PostgreSQL / outbox      |
| Confirmações disponíveis para recuperação | Kafka                    |
| Progresso já processado                   | Instituição participante |
| Acesso ao histórico                       | Notification Gateway     |

## 1. Fluxo de um pagamento

O pagamento possui poucos estados:

```mermaid
stateDiagram-v2
    [*] --> WAITING_ACCEPTANCE: pagamento admitido
    [*] --> REJECTED: saldo insuficiente
    WAITING_ACCEPTANCE --> SETTLED: recebedor aceita
    WAITING_ACCEPTANCE --> REJECTED: recebedor rejeita
    SETTLED --> [*]
    REJECTED --> [*]
```

Uma instituição inicia um pagamento enviando uma `pacs.008`.

A identidade da instituição é obtida do certificado mTLS usado no ingresso. Apenas a instituição da conta pagadora pode iniciar o pagamento.

Se houver saldo suficiente, o pagamento entra em `WAITING_ACCEPTANCE`. O valor já é retirado do saldo disponível nesse momento, antes da decisão do recebedor.

O recebedor recebe a solicitação e responde com uma `pacs.002`. Apenas a instituição recebedora pode decidir o resultado do pagamento.

Se aceitar, o pagamento passa para `SETTLED` e o recebedor é creditado.

Se rejeitar, o pagamento passa para `REJECTED` e o valor reservado volta ao saldo disponível do pagador.

Quando não há saldo suficiente na entrada, o pagamento é rejeitado imediatamente e não chega a `WAITING_ACCEPTANCE`.

## 2. Corretude financeira

### Saldo e reserva

Cada instituição possui um único saldo disponível no PostgreSQL.

Não há uma tabela separada de reservas. A reserva é representada pela combinação entre o estado do pagamento e o saldo disponível:

```text
payment = WAITING_ACCEPTANCE

        ↓

o valor daquele pagamento já saiu
do saldo disponível do pagador
```

Na prática:

| Situação           | Efeito                                       |
| ------------------ | -------------------------------------------- |
| pagamento admitido | retira o valor da disponibilidade do pagador |
| recebedor aceita   | credita o recebedor                          |
| recebedor rejeita  | devolve o valor à disponibilidade do pagador |

Quando o pagamento é aceito, o pagador não é debitado novamente. O valor já deixou o saldo disponível quando o pagamento entrou em `WAITING_ACCEPTANCE`.

Isso evita que pagamentos concorrentes usem um valor que já está comprometido. Na conclusão, aceitar significa creditar o recebedor; rejeitar significa devolver a reserva.

O banco também impede que um saldo fique negativo.

### Idempotência

Receber a mesma mensagem duas vezes não pode resultar em duas transferências.

Cada pagamento possui uma identidade lógica (`paymentId` / `EndToEndId`) e uma representação normalizada de seu conteúdo.

Na entrada, existem três possibilidades:

| Caso                                  | Comportamento                                           |
| ------------------------------------- | ------------------------------------------------------- |
| identidade nova                       | o pagamento segue normalmente                           |
| mesma identidade e mesmo conteúdo     | a repetição não produz novo efeito                      |
| mesma identidade e conteúdo diferente | a mensagem é tratada como conflito e enviada para a DLQ |

Uma repetição válida não altera saldo novamente, não gera outro fato de auditoria e não cria uma nova obrigação de notificação.

A mesma regra cobre concorrência entre duas cópias da mesma requisição: apenas uma delas consegue registrar o pagamento como novo.

Isso também vale para a resposta do recebedor. Repetir a mesma decisão depois que a transição já foi aplicada não gera outro crédito ou estorno. Se a nova decisão for incompatível com o estado atual, ela é tratada como conflito.

Mensagens podem, portanto, aparecer mais de uma vez. O efeito financeiro associado a elas não.

### Concorrência

Além de mensagens duplicadas, pagamentos diferentes podem tentar consumir o mesmo saldo ao mesmo tempo.

A serialização financeira acontece no PostgreSQL.

Antes de avaliar quais pagamentos cabem no saldo de uma instituição, a transação bloqueia a linha correspondente. Enquanto essa transação está decidindo e alterando o saldo, outras operações que dependem da mesma linha aguardam.

Dentro de um lote, os pagamentos de uma mesma conta são avaliados em uma ordem determinística.

Por exemplo:

```text
saldo disponível = 100

pagamento de 80 → entra
pagamento de 50 → rejeita por saldo insuficiente
pagamento de 10 → entra
```

A rejeição do pagamento de 50 não impede que o pagamento de 10 use o saldo restante.

Na conclusão dos pagamentos, o mesmo mecanismo protege as linhas envolvidas enquanto aceites creditam recebedores e rejeições devolvem valores reservados.

### Atomicidade

Bloquear o saldo resolve a concorrência, mas uma transição de pagamento ainda envolve várias alterações que representam o mesmo fato de negócio:

```text
estado do pagamento
+
movimentação financeira
+
auditoria
+
obrigação de enviar a confirmação
```

Todas essas alterações acontecem na mesma transação PostgreSQL.

Ao aceitar um pagamento, por exemplo, a transação persiste:

```text
WAITING_ACCEPTANCE → SETTLED
+
crédito do recebedor
+
PAYMENT_SETTLED na auditoria
+
confirmações que precisam ser publicadas
```

Ou tudo é persistido, ou nada é.

Assim, não existe um estado intermediário persistente em que:

* o pagamento aparece como concluído sem o crédito correspondente;
* o dinheiro muda sem a atualização do estado;
* o pagamento conclui sem uma obrigação durável de notificação;
* a auditoria registra algo que não foi aplicado.

A mesma regra vale para a admissão e para a rejeição de pagamentos.

A auditoria registra apenas mudanças efetivamente aplicadas. Uma repetição idempotente, que não altera o estado do negócio, também não gera um novo evento de auditoria.

## 3. Entrega recuperável das confirmações

A transação PostgreSQL cobre o estado financeiro e a criação da obrigação de notificar. A publicação no Kafka acontece depois dela e, portanto, precisa lidar com falhas nessa fronteira.

### Transactional outbox

A publicação no Kafka não participa da mesma transação que altera pagamentos e saldos.

Por isso, a confirmação a ser enviada é primeiro gravada em uma transactional outbox:

```text
transação PostgreSQL

├── pagamento
├── saldos
├── auditoria
└── notification_outbox
          │
          │ depois do commit
          ▼
        Kafka
```

A linha da outbox é criada junto com a alteração financeira.

Depois do commit, o SPI publica a confirmação no Kafka. A linha só é removida da outbox quando o broker confirma as mensagens correspondentes.

Se o processo cair antes da publicação, a confirmação continua registrada no PostgreSQL e pode ser retomada após a reinicialização.

Quando o resultado de uma publicação fica incerto, o lote pode ser enviado novamente. Isso pode produzir uma publicação duplicada, mas evita perder uma confirmação já criada.

### Kafka e entrega at-least-once

Depois que a publicação é confirmada, o Kafka mantém o histórico das confirmações disponíveis para entrega.

Não existe um segundo banco para acompanhar individualmente o estado de entrega de cada mensagem. As instituições acessam o histórico pelo Notification Gateway e usam um cursor para indicar até onde já processaram.

Esse cursor é autenticado e vinculado à instituição que o recebeu.

A instituição só avança o cursor depois de processar o lote de forma durável.

Se houver uma falha antes desse avanço, ela pode reutilizar o cursor anterior e receber parte das mensagens novamente.

A entrega é, portanto, **at-least-once**. O sistema não garante que uma confirmação será entregue fisicamente uma única vez.

Cada confirmação possui um identificador estável (`communicationId`), que permite reconhecer uma repetição sem reaplicar seu efeito lógico.

O tópico de notificações mantém sete dias de histórico.

O Gateway também mantém uma janela recente em memória para acelerar o caminho normal. Essa memória funciona apenas como otimização. Depois de uma reinicialização, ou quando o cursor aponta para uma posição mais antiga, o histórico volta a ser lido do Kafka.

### O que acontece quando há falhas

| Falha                                                   | Comportamento                                                                            |
| ------------------------------------------------------- | ---------------------------------------------------------------------------------------- |
| a transação PostgreSQL não conclui                      | pagamento, dinheiro, auditoria e obrigação de notificar não são persistidos parcialmente |
| o commit acontece, mas o processo cai antes de publicar | a confirmação continua registrada na outbox                                              |
| o resultado da publicação fica incerto                  | o lote pode ser publicado novamente                                                      |
| a instituição falha antes de avançar o cursor           | o cursor anterior pode ser reutilizado e as mensagens podem ser entregues novamente      |
| o Gateway reinicia                                      | o histórico necessário continua disponível no Kafka                                      |

Nos pontos em que não é possível tornar duas operações atomicamente únicas, o sistema aceita repetição e torna o processamento idempotente. Uma falha pode gerar uma nova tentativa, mas não um segundo efeito financeiro nem a perda de uma confirmação já persistida.

## 4. Separação de responsabilidades

Cada componente mantém o tipo de estado para o qual foi escolhido:

```text
PostgreSQL

→ quem possui o dinheiro?
→ em que estado está o pagamento?
→ qual obrigação nasceu junto com essa mudança?

Kafka

→ quais confirmações ainda podem ser recuperadas?

Instituição participante

→ até onde eu já processei?

Notification Gateway

→ como eu acesso esse histórico?
```

O PostgreSQL mantém o estado financeiro porque pagamento, saldo, auditoria e criação da obrigação de notificar precisam participar da mesma transação.

Depois da publicação, o Kafka passa a manter o histórico durável usado para recuperação das confirmações.

Cada instituição é responsável por saber até onde processou esse histórico de forma durável.

O Notification Gateway fornece o protocolo de acesso ao histórico, mas não mantém a posição de processamento como estado autoritativo.

Com essa divisão, o PostgreSQL não precisa acompanhar entregas individuais, e o Kafka não participa da definição de saldo ou estado financeiro.

Também não há estado mantido apenas em memória que seja necessário para reconstruir a situação correta do sistema.

## 5. Limites e trade-offs

O sistema qualificado atualmente possui alguns limites de ambiente e algumas escolhas explícitas de protocolo.

### Limites do ambiente

* o núcleo qualificado usa uma única instância de cada serviço;
* o Kafka local usa um único broker e fator de replicação 1;
* o protocolo de recuperação é exercitado, mas não a alta disponibilidade do broker;
* escala horizontal, operação multi-região e Kubernetes não fazem parte da qualificação atual.

### Trade-offs do protocolo

* as confirmações ficam disponíveis no fluxo operacional por sete dias; recuperações além dessa janela dependem dos mecanismos de recuperação de desastre;
* pagamentos em `WAITING_ACCEPTANCE` não possuem timeout automático; se o recebedor não responder, o valor pode permanecer reservado;
* saldo insuficiente rejeita o pagamento imediatamente; não existe fila de liquidez.

Dentro desses limites, as propriedades garantidas pelo sistema são **corretude financeira, idempotência, atomicidade e entrega recuperável**.
