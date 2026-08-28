# Cleanup — Kafka Producer

## Estado

| Gate A — negócio | Gate B — técnica | estado | próxima ação |
| --- | --- | --- | --- |
| aprovado | aprovado | concluído | nenhuma; etapa encerrada |

## Objetivo essencial aprovado

Ser a borda autenticada de ingresso dos PSPs: receber PACS.008 e PACS.002, convertê-los em mensagens internas individuais, propagar a identidade autenticada e confirmar a requisição somente após o Kafka aceitar os records.

## Inventário de negócio aprovado

| funcionalidade de negócio | decisão |
| --- | --- |
| autenticar o PSP por mTLS e extrair o ISPB do certificado | manter |
| receber PACS.008 e PACS.002 | manter |
| converter somente mensagens representáveis sem ambiguidade no contrato interno | manter |
| produzir um record interno por pagamento ou status | manter |
| preservar o conteúdo relevante da mensagem externa | manter |
| propagar o ISPB autenticado ao SPI | manter |
| responder sucesso somente após a confirmação do Kafka | manter |
| decidir se o pagador do PACS.008 corresponde ao PSP autenticado | remover; o SPI é o dono da autorização |

## Contratos aprovados

* HTTP `2xx` significa que a mensagem foi autenticada, convertida e confirmada pelo Kafka; não significa que o pagamento foi admitido pelo SPI.
* Em publicação parcial, a borda retorna `5xx`; o PSP pode repetir o envelope inteiro e a idempotência do SPI absorve os records já publicados.
* Payload PACS sintaticamente inválido ou não representável no contrato interno retorna `400`; falha de autenticação retorna `401`; falha Kafka ou interna retorna `5xx`.
* O `403` síncrono decorrente da autorização duplicada será removido. A tentativa não autorizada será classificada pelo SPI e seguirá sua política de DLQ.
* O PACS.002 continua sendo autorizado exclusivamente pelo SPI porque depende do estado persistido do pagamento.
* O Producer valida somente a sintaxe externa e as precondições inevitáveis para uma conversão sem ambiguidade. O SPI permanece autoritativo sobre validade semântica, autorização e regras de negócio e mantém validação defensiva da fronteira Kafka.

## Próximas decisões

- [x] Aprovar o objetivo essencial, o inventário de negócio e os contratos externos.
- [x] Inventariar a implementação e classificar complexidade essencial, acidental, código morto, ambiguidades e failure paths.
- [ ] Aprovar a intervenção técnica no Gate B antes de alterar código.

## Diagnóstico técnico

### Baseline

* 13 classes de produção e 4 classes de teste.
* 34 testes aprovados.
* Nenhum `TODO`, `FIXME`, `HACK` ou código morto evidente no source set atual.

### Complexidade essencial preservada

* servidor HTTP/2 com mTLS;
* extração da identidade autenticada do certificado;
* mapeamento dos envelopes PACS para o protobuf interno;
* publicação assíncrona com confirmação do Kafka;
* propagação do ISPB autenticado como chave e header;
* interfaces pequenas que isolam HTTP, publicação e cliente Kafka e tornam os contratos testáveis.

### Complexidade acidental e failure paths

* A autorização do pagador no Producer duplica uma regra autoritativa do SPI, introduz `PspAuthorizationException` e um contrato HTTP `403` que não pertence a esta fronteira.
* Payloads inválidos geram `IllegalArgumentException`, `ArithmeticException` ou `NullPointerException`, mas o servidor classifica todos como `500`; defeito interno e entrada inválida ficam indistinguíveis.
* Dois `KafkaProducer` com propriedades idênticas mantêm buffers, metadata e recursos separados apenas porque os records vão para tópicos diferentes; o cliente Kafka é thread-safe e publica em múltiplos tópicos.
* O alias `SPRING_KAFKA_BOOTSTRAP_SERVERS` é resíduo da implementação Spring anterior; o serviço atual não usa Spring.
* A agregação do corpo HTTP não possui limite explícito. A definição de um tamanho máximo altera o contrato externo e ficará fora desta limpeza até existir um limite de negócio homologado.
* A publicação parcial é inerente a um envelope transformado em vários records. O comportamento aprovado permanece: qualquer falha retorna `5xx` e o PSP pode repetir o envelope inteiro.

### Decisões de preservação

* Não criar um módulo compartilhado apenas para eliminar a cópia idêntica do protobuf entre Producer e SPI; isso adicionaria acoplamento de build maior que a duplicação atual. A compatibilidade continua protegida pelos testes de cada fronteira.
* Não dividir o mapper de 342 linhas: a maior parte do arquivo descreve a estrutura externa e a divisão espalharia um único contrato sem reduzir responsabilidade.
* Não remover `PaymentPublisher`, `ProducerClient` ou `SendCallback`: são seams pequenos, usados diretamente pelos testes, e evitam acoplar o servidor HTTP ao cliente Kafka concreto.
* Não adicionar validação completa do domínio PACS. O Producer valida apenas o necessário para converter sem inventar valores; o SPI continua validando o contrato interno e as regras de negócio.

## Intervenção proposta para o Gate B

1. Remover a autorização do pagador, `PspAuthorizationException`, o tratamento `403` e seus testes específicos.
2. Introduzir uma categoria explícita de payload PACS inválido, normalizar nela falhas esperadas de leitura/conversão e responder `400`, preservando `500` para defeitos internos e falhas Kafka.
3. Usar uma única instância thread-safe de `KafkaProducerClient` para os dois tópicos, preservando as interfaces atuais e a confirmação individual de cada record.
4. Substituir no Compose o alias Spring por `KAFKA_BOOTSTRAP_SERVERS` e remover o fallback legado do `AppConfig`.
5. Preservar o comportamento de publicação parcial, o protocolo interno, os tópicos, o mTLS e os parâmetros Kafka homologados.

## Validação proposta

* suíte Maven completa;
* contrato HTTP cobrindo `2xx`, `400`, `401` e `500`;
* publicação de PACS.008 e PACS.002 nos tópicos corretos pelo mesmo cliente;
* confirmação de todos os records antes de `2xx` e `5xx` quando qualquer publicação falha;
* identidade autenticada preservada na chave e no header;
* `git diff --check`.

## Resultado e evidências

* A autorização duplicada, o `403` e `PspAuthorizationException` foram removidos; o SPI permaneceu como único dono da autorização do pagador.
* PACS inválido agora possui classificação explícita e retorna `400`; falhas Kafka e defeitos internos permanecem `500`.
* Uma única instância thread-safe do cliente Kafka publica nos dois tópicos, eliminando buffers, metadata e lifecycle duplicados.
* O serviço passou a usar somente `KAFKA_BOOTSTRAP_SERVERS`; o alias legado Spring foi removido do código e do Compose.
* `AppConfig` permaneceu como fonte tipada única da configuração runtime. O override não utilizado de `SERVER_PORT` foi removido; a porta homologada ficou fixa em `8001`, enquanto bootstrap Kafka e caminhos TLS permanecem parâmetros de deployment.
* O contrato de publicação parcial, a identidade autenticada, o mTLS, o protobuf, os tópicos e os parâmetros Kafka foram preservados.
* `./mvnw test` executou 36 testes sem falhas, erros ou skips.
* `docker compose config --quiet` e `git diff --check` passaram.

## Conclusão

- [x] Gate A aprovado.
- [x] Diagnóstico técnico concluído.
- [x] Gate B aprovado.
- [x] Intervenção implementada e validada.
