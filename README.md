# Instant Payment System

O Pix é o sistema brasileiro de pagamentos instantâneos. Para quem usa, a experiência parece simples: escolher quem vai receber, informar um valor e enviar. O dinheiro deve se mover uma única vez, chegar à pessoa certa, e o usuário deve saber rapidamente se o pagamento foi concluído ou rejeitado.

Por trás dessa experiência existe um problema de engenharia muito maior: preservar esse comportamento enquanto milhares de pagamentos chegam a cada segundo, mensagens se repetem, operações concorrem e componentes podem falhar.

Foi esse problema que motivou este projeto.

Ele não tenta reproduzir a infraestrutura de produção do Banco Central. É uma implementação própria, menor e deliberadamente limitada, construída para explorar uma pergunta:

> Quanto tráfego de pagamentos um sistema consegue sustentar sem abrir mão das propriedades que fazem cada pagamento funcionar corretamente?

## O que foi construído

O núcleo principal implementa o caminho completo entre o envio de um pagamento e a observação de seu resultado: ingresso autenticado, processamento financeiro, decisão do recebedor, liquidação ou rejeição, auditoria e entrega durável das notificações aos participantes.

Uma ferramenta de carga independente gera pagamentos, acompanha os resultados recebidos pelos PSPs simulados e verifica se o sistema preservou tanto a carga temporal quanto os resultados esperados.

O repositório também contém uma demonstração de referência com dois PSPs, um diretório de chaves e uma interface web. Ela torna o fluxo visível para uma pessoa, mas não participa das garantias de performance, durabilidade ou disponibilidade do núcleo.

## O que o sistema demonstrou

O núcleo final foi qualificado duas vezes com a mesma revisão limpa e a mesma carga. Cada execução ofereceu 2.100 pagamentos originais por segundo durante 15 minutos. Para qualificar, toda janela contínua de um segundo precisava conter pelo menos 2.000 pagamentos originais iniciados.

| Resultado | Run A | Run B |
| --- | ---: | ---: |
| Menor taxa em uma janela contínua | 2.017 TPS | 2.079 TPS |
| Latência end-to-end p99 | 855 ms | 265 ms |
| Pagamentos originais executados | 1.889.369 | 1.890.000 |
| Violações funcionais ou de replay | 0 | 0 |

As duas execuções satisfizeram o piso temporal e o limite interno de p99 abaixo de um segundo de forma independente. A execução mais lenta foi preservada em vez de descartada: ela demonstra que o sistema continuou dentro do contrato na condição menos favorável observada e deixa a variância do experimento visível.

## Por que esse resultado não é apenas um número

O benchmark não mede somente quantas requisições o ingresso aceitou. Cada pagamento continua atravessando o trabalho que dá significado ao resultado:

- **O mesmo pagamento não pode mover dinheiro duas vezes.** Uma repetição idêntica não produz outro efeito; reutilizar a mesma identidade com conteúdo diferente é um conflito.
- **Dinheiro comprometido não pode continuar disponível.** Ao admitir um pagamento, o sistema reserva o valor antes que o recebedor decida o resultado.
- **O resultado precisa concordar com o dinheiro.** Estado do pagamento, alteração de saldo, fato de auditoria e obrigação de publicar o resultado pertencem à mesma transação.
- **Uma falha não pode apagar silenciosamente um resultado confirmado.** O banco protege a criação da notificação, Kafka mantém o histórico de entrega e o PSP controla até onde processou.
- **A ferramenta de carga não pode esconder atraso.** Trabalho que perdeu sua janela temporal não é acumulado e despejado depois; picos posteriores não compensam um período abaixo do piso.

Portanto, corretude e performance não são duas afirmações separadas. A corretude define qual trabalho precisa realmente acontecer; a arquitetura preserva essas propriedades; o benchmark mede quanto desse trabalho o sistema sustenta.

## Como um pagamento funciona

Primeiro, o modelo mental:

~~~mermaid
flowchart LR
    Payer[Pagador] -->|envia o pagamento| Core[Sistema de pagamentos]
    Core -->|reserva o valor| Decision[Decisão do recebedor]
    Decision -->|aceito| Settle[Credita o recebedor]
    Decision -->|rejeitado| Release[Devolve a disponibilidade ao pagador]
    Settle --> Outcome[Resultado durável]
    Release --> Outcome
~~~

No núcleo, essas responsabilidades são distribuídas assim:

~~~mermaid
flowchart LR
    PSP[PSP] -->|HTTP/2 + mTLS| Ingress[Payment Ingress]
    Ingress -->|mensagens PACS| Payments[(Kafka)]
    Payments --> SPI[Payment Processor - SPI]
    SPI -->|pagamento + saldos + auditoria + outbox| DB[(PostgreSQL)]
    SPI -->|log durável de notificações| Notifications[(Kafka)]
    Notifications --> Gateway[PSP Notification Gateway]
    Gateway -->|Pull + cursor| PSP
~~~

- **Payment Ingress** (kafka-producer) autentica o PSP e coloca pagamentos e status no Kafka.
- **Payment Processor** (spi) é a autoridade sobre estado, saldos, auditoria, idempotência e criação das obrigações de notificação.
- **PSP Notification Gateway** (notification-gateway) entrega resultados com semântica at-least-once por meio de Pull e cursor autenticado.
- **Load Test Harness** (load-test) gera a carga sem depender do SPI, observa os resultados finais e reconstrói a taxa depois da execução.

O [rascunho do design do sistema](docs/design.md) detalha como essas escolhas preservam as propriedades introduzidas acima.

## Por que confiar no benchmark

A carga qualificadora contém 80% de pagamentos concluídos e 20% de rejeições determinísticas por saldo insuficiente. Ela concentra 80% do tráfego em pares quentes e acrescenta 5% de repetição tanto para pagamentos quanto para mensagens de status.

HTTP 2xx indica somente que o ingresso aceitou a requisição. O resultado funcional é observado depois, pela notificação recebida pelo pagador: ACSC para um pagamento concluído ou RJCT com motivo AM04 para saldo insuficiente. Ausência, status contraditório ou motivo incompatível são violações.

O gerador Rust admite um pagamento apenas quando o payload e a capacidade real de um stream HTTP/2 estão prontos antes do limite temporal. Ele registra o início efetivo da requisição e calcula, depois do experimento, o menor número de originais presente em qualquer janela contínua de um segundo. Assim, atraso seguido de compensação não pode se passar por carga sustentada.

O perfil, o plano normalizado, os relatórios das duas execuções e seus checksums estão versionados. O [rascunho de performance e evidência](docs/performance.md) explicita carga, ambiente, fronteira de medição, resultados e limitações.

## Onde as afirmações terminam

A evidência vale para uma instância de cada componente do núcleo no ambiente local e sob os recursos documentados. Ela não demonstra capacidade nacional do Pix, equivalência com a arquitetura interna do Banco Central, alta disponibilidade de produção, operação em múltiplas regiões, Kubernetes ou escala horizontal.

Kafka foi exercitado com um broker e fator de replicação 1. A retenção de notificações por sete dias é uma fronteira operacional, não um mecanismo de recuperação de desastre. O gerador e a stack compartilharam o mesmo host, embora o consumo do gerador não faça parte do orçamento atribuído aos serviços.

## Executar

O host precisa de Linux, Docker e Docker Compose. Para preparar uma stack limpa e executar a carga funcional:

~~~bash
cd load-test
./prepare-performance-environment.sh --profile mixed-outcomes-smoke
./run-load-test.sh --profile mixed-outcomes-smoke smoke
~~~

O preparador recria os volumes do PostgreSQL e Kafka, constrói os serviços, gera certificados locais, aguarda a prontidão e provisiona os participantes. Ele não gera tráfego de benchmark.

Para reproduzir o perfil qualificador, faça uma preparação nova antes de cada execução:

~~~bash
cd load-test
./prepare-performance-environment.sh --profile mixed-outcomes-2k-15m
./run-load-test.sh --profile mixed-outcomes-2k-15m qualification
~~~

Os resultados são escritos em load-test/results/&lt;run-tag&gt;/&lt;timestamp&gt;/. Uma nova execução é uma reprodução independente; a afirmação promovida já possui [evidência compacta versionada](docs/performance/evidence/2026-08-29/manifest.md).

## Aprofundar

- [Design do sistema e corretude](docs/design.md)
- [Metodologia, resultados e evidência](docs/performance.md)
- [Evidência versionada das execuções qualificadoras](docs/performance/evidence/2026-08-29/manifest.md)
- [Demonstração de referência](demo/README.md)
