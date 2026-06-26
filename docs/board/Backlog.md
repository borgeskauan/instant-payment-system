# Backlog

Este arquivo guarda trabalho possível, mas ainda não priorizado. Tarefas ativas ou pausadas já priorizadas ficam em `docs/board/Atividades`.

## Backlog

### Validações do DICT

**Por que existe**

O DICT já valida alguns aspectos da chave Pix, como tipo de chave e CPF/CNPJ da própria chave. Ainda falta decidir se ele também deve validar dados da conta e do proprietário ao criar uma chave Pix.

**Tarefas**

- [ ] Validar campos da conta ao criar chave Pix.
- [ ] Validar campos do proprietário ao criar chave Pix.
- [ ] Definir quais validações pertencem ao DICT e quais pertencem ao PSP.

### Consistência entre DICT oficial e cadastro local de chaves do PSP

**Por que existe**

O DICT oficial não expõe uma API para listar todas as chaves Pix de uma pessoa ou conta. Ele resolve vínculos por chave, cria/atualiza/remove vínculos e oferece mecanismos de eventos/sincronização, mas a visão de "minhas chaves Pix" precisa ser mantida pelo PSP custodiante em uma base local própria.

No ambiente local, o DICT usa PostgreSQL persistido, enquanto os PSPs ainda usam H2 em memória. Depois de reiniciar os PSPs, uma chave Pix antiga pode continuar apontando no DICT para uma conta que não existe mais no PSP atual ou que não é a conta visível no frontend. Isso faz a transferência liquidar para um identificador antigo, causando confusão no teste manual.

Também existe um comportamento perigoso no PSP: buscar uma conta por `BankAccountId` pode criar uma conta nova automaticamente. Esse fallback mascara erro de consistência durante settlement, porque o crédito/débito pode ser aplicado em uma conta criada implicitamente em vez de falhar de forma explícita.

Persistir o banco local dos PSPs não é prioridade agora, porque o escopo principal dos testes continua sendo SPI/Kafka/DICT e não durabilidade do PSP. A estratégia preferida para o ambiente manual é reset coordenado do estado local junto com remoção da criação implícita de contas no PSP.

**Tarefas**

- [ ] Manter fidelidade à API oficial: não criar endpoint no DICT para listar chaves por pessoa ou conta.
- [ ] Tratar o cadastro local do PSP como a fonte para "minhas chaves Pix", e o DICT como fonte de verdade para resolver/registrar/remover uma chave específica.
- [ ] Definir contrato para chave Pix já existente: retornar `409 Conflict` ou tratar como idempotente quando já pertencer à mesma conta.
- [ ] Impedir que falhas de negócio do DICT, como chave duplicada ou chave inexistente, virem `500`.
- [ ] Garantir atomicidade prática no cadastro: o PSP só deve salvar a chave local depois de sucesso no DICT; falha no DICT não pode deixar chave local órfã.
- [ ] Definir fluxo de remoção/alteração de chave mantendo consistência entre DICT e cadastro local do PSP.
- [ ] Avaliar uso futuro de eventos/sincronização do DICT oficial para reconciliar a base local do PSP.
- [ ] Separar lookup de conta de criação de conta no PSP; settlement deve buscar conta existente e falhar se ela não existir.
- [ ] Remover criação automática de conta em `CustomerBankAccountJpaAdapter.findById` ou restringir esse comportamento apenas ao fluxo explícito de criação de cliente.
- [ ] Registrar erro claro quando uma notificação de settlement referenciar conta local inexistente.
- [ ] Criar reset coordenado para o ambiente manual: limpar DICT junto com o estado efêmero dos PSPs ou recriar seed coerente depois do restart.
- [ ] Documentar que persistir banco local dos PSPs está fora do escopo atual.
- [ ] Adicionar teste para chave Pix apontando para conta inexistente no PSP: fluxo deve falhar de forma explícita e observável, sem criar conta implicitamente.

### Consultas auxiliares simuladas

**Por que existe**

O fluxo Pix real depende de consultas e validações auxiliares, como CPF/CNPJ. No projeto, ainda não está claro se isso deve virar um microserviço separado, uma responsabilidade do DICT ou apenas uma validação interna simplificada.

**Tarefas**

- [ ] Decidir onde ficará a consulta/validação de CPF/CNPJ.
- [ ] Implementar consulta simulada de CPF/CNPJ, se ainda fizer sentido para o escopo.

### Contrato de preview e execução no PSP

**Por que existe**

Hoje o endpoint de execução de transferência recebe o objeto completo do recebedor retornado pelo preview. Isso reaproveita a consulta ao DICT e mantém o `/transfer/execute` rápido, mas acopla o cliente ao formato interno do recebedor. Uma alternativa melhor é o preview gerar um `previewId`, armazenar temporariamente os dados resolvidos no PSP pagador e fazer o execute receber apenas esse identificador, junto com os dados da transferência.

Os testes de carga atuais em `load-test/` chamam diretamente o fluxo SPI/Kafka por PACS e não dependem dos endpoints `/transfer/preview` e `/transfer/execute` do PSP.

**Tarefas**

- [ ] Fazer `/transfer/preview` retornar um `previewId` além dos dados exibíveis do recebedor.
- [ ] Armazenar temporariamente o resultado do preview no PSP pagador, com expiração.
- [ ] Alterar `/transfer/execute` para receber `previewId` em vez do corpo completo do `receiver`.
- [ ] Validar expiração, reuso e existência do preview antes de executar a transferência.
- [ ] Atualizar Bruno e REST Client para o novo contrato.
- [ ] Manter os testes de carga atuais compatíveis, já que eles exercitam SPI/Kafka diretamente.

### Auditoria completa das transações no SPI sem pesar o hot path

**Por que existe**

Para reduzir pressão no PostgreSQL durante os testes de carga, o SPI passou a persistir no caminho quente apenas os campos necessários para liquidação e roteamento da confirmação: `payment_id`, `amount`, `status`, `sender_bank_code` e `receiver_bank_code`.

Isso ajuda a medir o impacto de uma tabela operacional estreita, mas não resolve a necessidade real de auditoria. Em produção, o sistema precisa conseguir reconstituir a transação completa, incluindo payload recebido, dados das partes e metadados de origem. Essa auditoria não deve voltar a bloquear a liquidação dentro do SLA.

**Tarefas**

- [ ] Definir o modelo de auditoria completo da transação.
- [ ] Decidir se a fonte auditável principal será Kafka, tabela dedicada, outbox ou combinação desses mecanismos.
- [ ] Criar fluxo assíncrono para persistir payload/dados completos sem bloquear o settlement.
- [ ] Registrar metadados de origem: tópico, partição, offset, timestamp de consumo e identificador fim a fim.
- [ ] Manter tabela operacional estreita para o caminho quente de liquidação.
- [ ] Definir política de retenção e consulta para dados de auditoria.
- [ ] Medir o impacto da auditoria assíncrona no load test antes de considerá-la parte do fluxo padrão.

### Observabilidade operacional do fluxo Pix

**Por que existe**

O projeto já tem testes de carga, JFR, traces CSV e métricas de infraestrutura no ambiente local, mas ainda falta uma visão operacional clara para responder rapidamente se o sistema está saudável, se houve degradação e onde está o gargalo. A observabilidade precisa cobrir tanto saúde técnica dos serviços quanto o fluxo de negócio Pix ponta a ponta.

As metas principais são: verificar saúde da aplicação enquanto ela roda, acompanhar throughput e latência do fluxo, detectar degradação de SLA, identificar gargalos entre Kafka, SPI, PostgreSQL e notification-gateway, e comparar o comportamento antes/depois de mudanças.

**Tarefas**

- [ ] Definir métricas de saúde por serviço: container/pod up, restarts, readiness/liveness, CPU, memória, CPU throttling, JVM heap, direct memory e GC.
- [ ] Definir métricas Kafka: consumer lag por tópico/grupo/partição, records consumed/sec, records produced/sec, producer latency, retries, errors e rebalances.
- [ ] Definir métricas do SPI: requests consumidas/sec, statuses recebidos/sec, settlements/sec, notificações enfileiradas/sec, latência de processamento, erros e tempo de queries no PostgreSQL.
- [ ] Definir métricas do notification-gateway: subscribers ativos, notificações/sec, batches/sec, tamanho dos batches, flush por tamanho/tempo, erros gRPC e streams cancelados.
- [ ] Definir métricas de banco: conexões ativas, pool Hikari, query latency, locks, CPU, I/O e slow queries.
- [ ] Definir métricas de negócio/SLA: transações iniciadas, aceitas, confirmadas, não confirmadas, dentro/fora do SLA, p50, p95, p99 e máximo.
- [ ] Criar dashboard de saúde geral do ambiente: serviços, containers/pods, CPU, memória, restarts, throttling, Kafka e PostgreSQL.
- [ ] Criar dashboard do fluxo Pix ponta a ponta: incoming, consumed, settled, outbound status, notifications e confirmations.
- [ ] Criar dashboard de latência por etapa: `http_done -> request_consumed`, `request_consumed -> pacs008_received`, `pacs002_sent -> confirmation_received` e latência end-to-end.
- [ ] Criar dashboard de Kafka: lag, throughput, producer/consumer rate, partitions e rebalances.
- [ ] Criar dashboard de cold start: tempo até readiness, tempo até primeiro consumo, lag inicial, drain rate e tempo até estabilizar p95/p99.
- [ ] Definir alertas mínimos para degradação: container down, restart loop, CPU throttling alto, consumer lag crescendo, p95/p99 acima do alvo, transações não confirmadas e erros Kafka/gRPC/DB.
- [ ] Padronizar labels/dimensões das métricas: serviço, tópico, consumer group, partition, ISPB, tipo de evento e versão/build.
- [ ] Decidir quais métricas vêm de Micrometer/Actuator, Kafka exporter, Postgres exporter, cAdvisor/Node exporter, JFR ou traces próprios.
- [ ] Integrar a observabilidade aos testes de carga para comparar baseline vs mudança: throughput sustentado, p95/p99, lag, CPU por transação e memória por transação.

### Gating de prontidão dos microserviços

**Por que existe**

Os microserviços podem subir tecnicamente antes de estarem prontos para servir tráfego real. No fluxo atual, isso afeta principalmente o cold start: a aplicação pode aceitar carga antes de aquecer conexões, Kafka producers/consumers, gRPC streams, pools, caches e caminhos críticos de serialização/DB. Isso cria ruído nos testes e pode gerar degradação logo após deploy ou restart.

A meta é separar "processo está vivo" de "serviço está pronto para tráfego real". Cada serviço deve expor readiness apenas depois de validar dependências, inicializar recursos críticos e concluir o warmup necessário para o seu papel no fluxo Pix.

**Tarefas**

- [ ] Definir critérios de readiness por serviço: `kafka-producer`, SPI, notification-gateway, PSP, Kafka, PostgreSQL e DICT quando for separado.
- [ ] Separar liveness de readiness: liveness indica processo vivo; readiness indica apto a receber tráfego.
- [ ] Implementar readiness no `kafka-producer` apenas após conexão com Kafka, metadata dos tópicos carregada e producers aquecidos.
- [ ] Implementar readiness no SPI apenas após PostgreSQL/Flyway prontos, Kafka consumers criados, tópicos acessíveis, pools aquecidos e warmup do caminho crítico concluído.
- [ ] Implementar readiness no notification-gateway apenas após conexão com Kafka, listener ativo e servidor gRPC pronto.
- [ ] Implementar readiness no PSP apenas após conexão com notification-gateway, stream gRPC estabelecido, dependências HTTP/DICT disponíveis e warmup concluído.
- [ ] Definir se o serviço deve rejeitar tráfego com `503` enquanto não estiver ready.
- [ ] Atualizar Docker Compose para usar healthchecks que reflitam readiness real, não apenas porta aberta.
- [ ] Planejar adaptação futura para Kubernetes readiness/liveness probes.
- [ ] Integrar os gates ao script de load test para iniciar carga apenas depois de todos os serviços críticos estarem ready.
- [ ] Expor métricas de startup/warmup: tempo até liveness, tempo até readiness, tempo de warmup, falhas de dependência e último motivo de not-ready.
- [ ] Adicionar testes de contrato para readiness: dependência indisponível mantém serviço not-ready; warmup concluído torna serviço ready.

### Cenários realistas e reprocessamento no load-tool

**Por que existe**

O teste atual sustenta alta taxa com um padrão bastante controlado. Para aproximar o experimento de produção, o load-tool precisa fornecer perfis de teste automatizados para cenários diferentes: caminho feliz, saldo insuficiente, rejeições, duplicidade, replay, hot participants e variações de carga.

Kafka, retries, restarts e falhas de rede tornam duplicidade e replay inevitáveis. O fluxo precisa provar que reprocessar mensagens não duplica liquidação, que status já liquidado pode reemitir notificação de forma segura e que mensagens inválidas não travam o consumo.

**Tarefas**

- [ ] Adicionar perfis de teste no load-tool, com múltiplos arquivos `loadtool-config.json` por cenário.
- [ ] Permitir selecionar o perfil de teste no script de execução, por exemplo caminho feliz, saldo insuficiente, rejeições funcionais, duplicidade, replay e hot participants.
- [ ] Garantir que cada perfil defina carga, distribuição de participantes, valores, fundos provisionados, taxa esperada de confirmação e critérios de SLA.
- [ ] Gerar valores de transação variados em vez de valor fixo.
- [ ] Simular distribuição desigual entre ISPBs: poucos participantes quentes e muitos participantes frios.
- [ ] Criar cenários com hot ISPB, hot sender, hot receiver e hot partition.
- [ ] Variar taxa de chegada com ramp-up, pico, carga sustentada, queda e período ocioso.
- [ ] Misturar transações aprovadas e rejeitadas no mesmo run.
- [ ] Simular saldo insuficiente real para parte dos pagamentos.
- [ ] Medir impacto de rejeições e saldo insuficiente em throughput, p95/p99, consumer lag e uso de CPU.
- [ ] Validar que a taxa de confirmação considera apenas transações que deveriam confirmar.
- [ ] Reprocessar mensagens Kafka já consumidas e validar que não ocorre dupla liquidação.
- [ ] Reemitir status de pagamento já liquidado e validar replay idempotente da notificação.
- [ ] Testar duplicidade de `pacs.008` com o mesmo `EndToEndId`.
- [ ] Testar duplicidade de `pacs.002` para pagamento já confirmado.
- [ ] Validar que `notSettledPaymentIds` e atualizações de status continuam corretos com IDs duplicados.
- [ ] Expor métricas de duplicidade, replay e retries.
- [ ] Garantir que retry/replay não altera saldo nem gera confirmação inconsistente.
- [ ] Comparar cenário uniforme atual contra cenários realistas para identificar regressões escondidas.

### Dead letter queue para mensagens inválidas

**Por que existe**

Mensagens inválidas, incompatíveis com o contrato ou impossíveis de processar não devem travar o consumer group nem contaminar o caminho quente. O fluxo precisa isolar esses casos em uma DLQ com metadados suficientes para diagnóstico, replay controlado ou descarte consciente.

**Tarefas**

- [ ] Definir política de DLQ para falhas de parse, validação, contrato incompatível e erro inesperado de processamento.
- [ ] Criar tópicos DLQ para requests e status reports, com convenção de nomes clara.
- [ ] Publicar na DLQ o payload original e metadados: tópico original, partição, offset, timestamp, consumer group, erro, stack resumida e serviço origem.
- [ ] Garantir que mensagens enviadas para DLQ não travem o consumer group principal.
- [ ] Expor métricas de DLQ: mensagens/sec, total por motivo, total por tópico e idade da mensagem mais antiga.
- [ ] Criar logs estruturados para envio à DLQ sem logar payload sensível completo por padrão.
- [ ] Definir processo de replay controlado a partir da DLQ.
- [ ] Criar teste automatizado para payload inválido: mensagem vai para DLQ, consumer continua e fluxo saudável não é afetado.
- [ ] Criar teste automatizado para contrato antigo/incompatível, como PACS bruto chegando no tópico interno protobuf.
- [ ] Documentar quando uma mensagem deve ser reprocessada, corrigida manualmente ou descartada.

### Estabilizar teste de carga dentro do budget de CPU

**Por que existe**

Os testes recentes usaram ajustes de CPU para entender gargalos e validar otimizações. Para o experimento ficar repetível e servir de base para deploy em Kubernetes, o ambiente precisa estabilizar dentro de um budget fixo de aproximadamente 3 vCPUs por stack. Isso permite planejar capacidade para rodar duas stacks/instalações do conjunto de serviços no cluster sem depender de CPU extra durante o teste.

Nesse desenho, as duas stacks devem compartilhar o mesmo PostgreSQL. Isso precisa ser validado explicitamente, porque o banco vira um recurso comum entre as stacks e pode mudar o gargalo, a configuração de pools, locks, conexões e isolamento de dados.

**Tarefas**

- [ ] Definir o budget alvo de CPU por serviço dentro do limite total de 3 vCPUs por stack.
- [ ] Rebalancear CPU no Docker Compose para refletir o budget alvo, não apenas o melhor resultado local.
- [ ] Definir memória alvo por serviço junto com CPU para evitar OOM ou swap durante o load test.
- [ ] Rodar baseline de 15 minutos com o budget de 3 vCPUs e registrar throughput, p95, p99, max, lag, CPU e memória.
- [ ] Validar que o fluxo sustenta a meta de TPS dentro do SLA com o budget definido.
- [ ] Identificar qual serviço satura primeiro quando o budget total é respeitado.
- [ ] Ajustar concorrência de consumers/producers para o budget final, evitando configuração que só funciona com CPU excedente.
- [ ] Definir critério de estabilidade: variação aceitável entre runs, ausência de backlog residual e ausência de degradação progressiva.
- [ ] Criar cenário de repetição com múltiplos runs consecutivos após restart completo.
- [ ] Documentar o perfil final de recursos para Kubernetes: requests, limits e justificativa por serviço.
- [ ] Planejar execução com duas stacks/instalações no mesmo cluster compartilhando o mesmo PostgreSQL.
- [ ] Definir como separar dados, tópicos, consumer groups, ISPBs e métricas entre stacks quando o banco for compartilhado.
- [ ] Validar impacto do PostgreSQL compartilhado em conexões, locks, query latency, CPU, I/O e p95/p99.
- [ ] Validar isolamento de CPU/memória entre stacks mesmo com banco compartilhado.
- [ ] Atualizar scripts de load test para registrar automaticamente o perfil de CPU/memória usado no run.

### Infraestrutura e deploy

**Por que existe**

O projeto nasceu com intenção de rodar os serviços em containers e, futuramente, em Kubernetes. Já existem Dockerfiles e `infra/docker-compose.yml`, então esta frente precisa ser reavaliada antes de virar implementação.

**Tarefas**

- [ ] Conferir quais serviços já estão containerizados.
- [ ] Separar o que é ambiente local com Docker Compose do que seria deploy Kubernetes.
- [ ] Realizar deploy em Kubernetes, se ainda fizer sentido para o projeto.

### Control panel para PSPs

**Por que existe**

A ideia era ter uma interface ou serviço para criar, iniciar, parar e reiniciar PSPs sem depender de inicialização manual. Isso poderia ajudar em cenários com múltiplos PSPs e futura orquestração.

**Tarefas**

- [ ] Decidir se o control panel ainda é necessário.
- [ ] Definir escopo mínimo: criação, start, stop, restart ou apenas visualização.
- [ ] Decidir se isso pertence ao frontend atual, a um serviço separado ou à infraestrutura.

### Documentação geral do projeto

**Por que existe**

O projeto tem documentação espalhada sobre descoberta, fluxo Pix, testes de carga, Kafka e gRPC. Falta uma documentação de entrada que explique como o sistema está organizado e por onde retomar.

**Tarefas**

- [ ] Criar documentação geral do projeto.
- [ ] Mapear os principais serviços: SPI, PSP, DICT, Kafka producer e notification gateway.
- [ ] Explicar como executar o projeto localmente.
- [ ] Explicar o fluxo Pix ponta a ponta em alto nível.

## Histórico

### Definições iniciais

- [x] Definir endpoints dos sistemas: SPI, DICT e PSP.
- [x] Decidir se a estrutura do payload seguiria ISO 20022.
- [x] Definir fluxo de pagamento.
- [x] Definir stack técnica:
  - [x] Java + Spring Boot.
  - [x] Kafka.
  - [x] K6.
  - [x] Kubernetes.

### PACS

- [x] Fazer versão das PACSs para uso interno:
  - [x] pacs.008.
  - [x] pacs.002.
- [x] Converter PACSs para objeto interno e vice-versa:
  - [x] pacs.008.
  - [x] pacs.002.

### DICT

- [x] Implementar API do DICT.
- [x] Realizar implementação básica.
- [x] Barrar request se chave Pix já existe.
- [x] Validar CPF com dígito verificador e não existência prévia.
- [x] Criar enum para validar tipo de chave.

### SPI, PSP e fluxo Pix

- [x] Executar dois PSPs ao mesmo tempo.
- [x] Criar frontend em Angular para simular transações.
- [x] Criar endpoint de login/cadastro no PSP.
- [x] Criar endpoint de cadastro de chave Pix.
- [x] Realizar transação interbancária e intrabancária.
- [x] Melhorar infraestrutura para executar dois PSPs ao mesmo tempo.
- [x] Refatorar código do PSP para desacoplar o modelo usado do modelo do SPI.
- [x] Refatorar localização das classes e pacotes.
- [x] Testar implementação após refatoração.

### Testes de performance

- [x] Realizar teste de performance com K6.
- [x] Realizar vários pedidos de transferência.
- [x] Realizar pedido de transferência diretamente para SPI.
- [x] Realizar consulta de mensagens para um PSP.
- [x] Realizar aceite do pedido.
- [x] Consultar mensagens de confirmação e finalizar o ciclo de teste.
- [x] Implementar long polling de verdade.

### Kafka e notificações

- [x] Implementar tópico Kafka para notificações.
- [x] Criar microserviço de protótipo usando gRPC streaming.
- [x] Realizar comparação com HTTP long polling usando K6.
- [x] Implementar primeiro sem Kafka.
- [x] Desacoplar recebimento e processamento.
- [x] Criar microserviço para receber e postar no Kafka o pedido de transferência.
- [x] Integrar consumo do tópico Kafka no SPI para realizar processamento.
- [x] Criar tópico Kafka para tratar notificações.
- [x] Criar microserviço para conectar no tópico de notificações Kafka e expor endpoint gRPC de server-streaming.
- [x] Adequar teste para se integrar com microserviço gRPC.

### Infraestrutura

- [x] Implementar limite de recursos no teste de load balancer: CPU e RAM.
- [x] Padronizar PostgreSQL nos ambientes:
  - [x] Remover ou isolar a configuração H2 do SPI.
  - [x] Configurar PostgreSQL como banco padrão do SPI.
  - [x] Habilitar e revisar migrations com Flyway.
  - [x] Garantir que o ambiente local suba com PostgreSQL sem passos manuais extras.
