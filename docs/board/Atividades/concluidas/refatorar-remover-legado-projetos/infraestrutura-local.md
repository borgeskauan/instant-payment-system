# Cleanup — Ambiente local compartilhado

## Estado

| Gate A — operação | Gate B — técnica | estado | próxima ação |
| --- | --- | --- | --- |
| aprovado | aprovado | concluído | nenhuma; etapa encerrada |

## Objetivo essencial aprovado

Disponibilizar uma stack local single-node reproduzível, segura e consistente para executar o fluxo principal, com dependências inicializadas, recursos homologados e reset explícito para testes isolados.

## Inventário operacional aprovado

| capacidade observável | decisão |
| --- | --- |
| PostgreSQL e Kafka locais | manter |
| SPI, Kafka Producer e Notification Gateway na stack padrão | manter |
| criação determinística dos tópicos Kafka | manter |
| retenção de sete dias das notificações | manter |
| inicialização do `pg_stat_statements` para diagnósticos | manter |
| geração local de certificados mTLS | manter |
| health checks e ordem de inicialização | manter |
| limites homologados de CPU e memória | manter |
| portas expostas para desenvolvimento local | manter |
| Kafka UI | manter como profile opcional e fora do caminho padrão |
| DICT | separar; seu destino pertence à avaliação pós-projeto |
| reset completo com `down -v --remove-orphans` | manter |

## Contrato de persistência aprovado

O shutdown normal preserva PostgreSQL e Kafka. Somente o reset explícito com remoção de volumes apaga os dois. A assimetria anterior foi corrigida adicionando persistência ao PostgreSQL; o preparador de performance continua usando `down -v --remove-orphans` e iniciando cada baseline com estado limpo.

## Baseline

* `docker compose -f infra/docker-compose.yml config --quiet` passou;
* os profiles opcionais `dict` e `kafka-ui` são reconhecidos;
* a stack padrão resolve PostgreSQL, bootstrap PostgreSQL, Kafka, bootstrap Kafka, SPI, certificados, Kafka Producer e Notification Gateway;
* `bash -n infra/certs/generate-local-mtls-certs.sh` passou;
* nenhum `TODO`, `FIXME`, `HACK` ou código morto evidente foi encontrado nos arquivos de infraestrutura.

## Decisões concluídas

- [x] Aprovar objetivo, inventário e lifecycle de dados no Gate A.
- [x] Inventariar implementação, ownership dos testes, fontes de configuração e failure paths.
- [x] Aprovar a intervenção técnica no Gate B antes de alterar a infraestrutura.

## Diagnóstico técnico

### Complexidade essencial preservada

* o Compose define a topologia local, conectividade, recursos e dependências de inicialização;
* os initializers tornam explícitos os tópicos, partições, retenção e extensão PostgreSQL necessários;
* os volumes representam o lifecycle durável da stack e o reset explícito dos testes;
* o gerador de certificados fornece uma CA local estável, certificados de servidor e identidades PSP com ISPB na SAN;
* os profiles opcionais mantêm DICT e Kafka UI fora do caminho padrão;
* o preparador de performance continua sendo o dono da recriação limpa e da readiness completa; o Compose não ganhará health checks específicos dos protocolos das aplicações.

### Complexidade acidental e ownership incorreto encontrados na baseline

* somente Kafka possui volume, embora a documentação prometa persistência e reset simétricos para Kafka e PostgreSQL;
* o Compose repete defaults comportamentais do Notification Gateway já definidos no `application.yml`; conectividade e recursos pertencem ao Compose, enquanto o baseline da aplicação deve ter uma única fonte;
* Kafka UI usa `latest`, tornando uma ferramenta opcional capaz de mudar sem alteração no repositório;
* somente PostgreSQL possui `restart: unless-stopped`, criando um lifecycle diferente dos demais componentes sem contrato que o justifique;
* contratos do Compose estão testados dentro do SPI e do load-test; esses testes pertencem à infraestrutura;
* um teste do SPI afirma apenas que overrides removidos continuam ausentes e não protege comportamento vigente.

### Failure paths preservados

* falha no bootstrap Kafka ou PostgreSQL impede o início dos consumidores dependentes;
* topologia divergente do log de notificações falha explicitamente em vez de alterar silenciosamente a sequência;
* certificado parcialmente gerado falha com orientação de recuperação; rotação permanece explícita por ser uma operação destrutiva;
* readiness das aplicações continua externa ao Compose e limitada ao preparador de performance;
* credenciais e certificados permanecem deliberadamente locais e não representam um deployment produtivo.

Nenhum mecanismo morto foi comprovado no Compose, no Dockerfile ou no script de certificados. A duplicação entre os dois fluxos de criação de tópicos protege invariantes diferentes e será mantida. O README de certificados será preservado para a etapa final de documentação.

## Intervenção aprovada e executada

1. Adicionar `postgres-data` e montar `/var/lib/postgresql/data`, tornando `down` e `down -v` coerentes para os dois stores.
2. Remover `restart: unless-stopped` do PostgreSQL e manter failures visíveis e uniformes na stack local.
3. Remover do Compose os defaults comportamentais e caminhos TLS do Notification Gateway que já são idênticos aos defaults autoritativos da aplicação; manter somente endpoint Kafka e opções de JVM/deployment.
4. Fixar Kafka UI em `v0.7.2`, preservando o profile opcional.
5. Consolidar os contratos do Compose em um teste de infraestrutura: mover a verificação PostgreSQL do load-test, absorver os testes de tópicos atualmente no SPI e remover o teste negativo de overrides inexistentes.
6. Preservar tópicos, retenção, partições, recursos, certificados, portas, profiles e scripts sem refatoração cosmética.

## Resultado

* PostgreSQL e Kafka agora possuem volumes nomeados e seguem o mesmo lifecycle: `down` preserva os dados e `down -v` remove ambos;
* o PostgreSQL deixou de ter uma política de restart exclusiva da qual a stack não dependia;
* defaults comportamentais e caminhos TLS do Notification Gateway deixaram de ser repetidos no Compose; a aplicação voltou a ser a fonte autoritativa desses valores;
* Kafka UI permanece opcional e passou a usar a versão fixa `v0.7.2`;
* contratos de tópicos, retenção, volumes e configuração diagnóstica do PostgreSQL passaram a pertencer a `infra/tests/compose-contract-test.sh`;
* testes antigos do SPI e do load-test que assumiam ownership sobre o Compose foram removidos ou reduzidos ao contrato próprio do componente.

## Evidências

* `infra/tests/compose-contract-test.sh` passou;
* `docker compose -f infra/docker-compose.yml config --quiet` passou e reconheceu `postgres-data` e `kafka-data`;
* `bash -n infra/tests/compose-contract-test.sh infra/certs/generate-local-mtls-certs.sh` passou;
* a suíte do Notification Gateway passou com 40 testes;
* o teste diretamente afetado `KafkaConsumerConfigTest#performanceDefaultsDoNotFragmentAvailableStatusReportBatches` passou;
* `git diff --check` e as buscas finais por referências vigentes removidas passaram.

O preparador oficial recriou PostgreSQL e Kafka com volumes novos, construiu as imagens, aguardou readiness, capturou a configuração efetiva do SPI, provisionou fundos e gerou certificados. A suíte final do SPI passou com 197 testes, e o smoke integrado concluiu o fluxo completo sem violações.
