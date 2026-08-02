# Garantia de produção das notificações do SPI

- [ ] Garantir a produção durável das notificações originadas no SPI

**Por que existe**

Hoje o SPI confirma alterações no pagamento e nas posições financeiras no PostgreSQL e publica posteriormente as notificações no Kafka. Essas duas operações não compartilham a mesma garantia transacional.

Existe uma janela de falha em que o fato financeiro é confirmado, mas o processo cai ou o Kafka fica indisponível antes da publicação. Nesse cenário, o `notification-gateway` nunca recebe a obrigação de entrega e, dependendo do estado já persistido, uma redelivery pode tratar a mensagem como replay ou `NOOP` sem reconstruir a notificação perdida.

O fluxo de delivery já existente no `notification-gateway` protege a entrega depois que a notificação chega ao gateway, por meio de persistência, retry e ACK. Ele não resolve a perda que pode acontecer entre o commit financeiro no SPI e a publicação no Kafka.

**Intenção da mudança**

Garantir que toda obrigação de notificação destinada a um PSP seja registrada de forma durável e consistente com o fato de negócio que a originou, antes de depender da publicação no Kafka.

A feature deve abranger:

- acceptance request ao PSP recebedor;
- rejeição ao PSP pagador;
- confirmação ao pagador e ao recebedor após liquidação;
- impacto sobre a política atual de replay utilizada para reconstruir efeitos pendentes;
- preservação da idempotência existente por `communicationId`;
- continuidade do fluxo atual do `notification-gateway`, incluindo persistência, retry e ACK.

**Resultado esperado**

- Se o fato de negócio for confirmado, a obrigação correspondente de notificação não poderá ser esquecida pelo SPI.
- Uma indisponibilidade do Kafka ou queda do processo não poderá causar perda definitiva da notificação.
- A publicação pendente deverá continuar recuperável sem duplicar indevidamente a obrigação lógica já identificada pelo `communicationId`.
- O `notification-gateway` deverá continuar recebendo as notificações pelo fluxo interno e permanecer responsável pelo delivery tracking até o ACK do PSP.
- A política de replay deverá ser revisada para não depender da reconstrução circunstancial de efeitos cuja obrigação já deva estar persistida.

**Direção arquitetural provável**

A solução técnica provável envolve transactional outbox no SPI, registrada de forma consistente com a alteração de negócio que cria a obrigação de notificar.

Esta task ainda não fecha o desenho da outbox. Estrutura de dados, estratégia de publicação, concorrência, retry, limpeza, migrations, observabilidade e testes detalhados deverão ser definidos em uma etapa posterior de planejamento.

**Relação com outras frentes**

Esta feature complementa a confiabilidade já implementada no `notification-gateway`: a outbox deverá proteger o trecho entre o fato de negócio no SPI e a publicação Kafka, enquanto o gateway continuará protegendo o trecho entre o recebimento da notificação e o ACK do PSP.

A auditoria de negócio permanece pausada até que esta garantia seja implementada e a semântica definitiva de replay possa ser revisada.
