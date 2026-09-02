# Performance e evidência

O sistema foi qualificado em duas execuções consecutivas de 15 minutos. Nas duas, manteve pelo menos 2.000 pagamentos por segundo, p99 abaixo de 1 segundo e nenhum outcome esperado ausente ou contraditório.

Este documento registra o contrato, os resultados, o ambiente e os limites dessa afirmação.

## 1. Critérios de qualificação

Uma execução só qualifica se atender aos três critérios:

| Critério | Requisito |
| --- | --- |
| throughput | pelo menos 2.000 pagamentos originais em toda janela contínua de 1 segundo |
| latência | p99 end-to-end abaixo de 1 segundo |
| corretude observável | nenhum outcome esperado ausente ou contraditório e nenhuma falha nos replays selecionados |

A fase ativa oferece 2.100 pagamentos originais por segundo durante 15 minutos. Replays, respostas do recebedor e confirmações são trabalho adicional e não contam para o piso de throughput.

A origem dos limites e sua relação com referências públicas do Pix está no [README](../README.md).

## 2. Resultados

As duas execuções usaram o commit `1351ea564d0834a66e1b5d99a5e09a1a384cae1b`, o mesmo perfil, plano normalizado e instrumentação. A worktree estava limpa, e o ambiente foi recriado antes de cada uma.

| Resultado | Execução A | Execução B |
| --- | ---: | ---: |
| pagamentos planejados / executados | 1.890.000 / 1.889.369 | 1.890.000 / 1.890.000 |
| média na fase ativa | 2.099,299/s | 2.100,000/s |
| menor janela contínua de 1 segundo | 2.017/s | 2.079/s |
| p50 end-to-end | 188 ms | 142 ms |
| p95 end-to-end | 598 ms | 234 ms |
| p99 end-to-end | 855 ms | 265 ms |
| maior latência observada | 1.578 ms | 693 ms |
| replays `pacs.008` enviados / aceitos | 100.422 / 100.422 | 100.472 / 100.472 |
| replays `pacs.002` enviados / aceitos | 80.326 / 80.326 | 80.373 / 80.373 |
| outcomes ausentes / contraditórios | 0 / 0 | 0 / 0 |
| falhas nos replays | 0 | 0 |

As duas execuções qualificaram independentemente. A execução A foi a condição menos favorável: ficou 17 pagamentos acima do piso na pior janela e chegou a 855 ms de p99. A B teve margem maior.

Por isso, nem a média de 2.100 pagamentos por segundo nem o p99 de 265 ms representam sozinhos o resultado. A afirmação sustentada é:

> Nas duas qualificações consecutivas, o sistema permaneceu acima de 2.000 pagamentos por segundo, com p99 entre 265 e 855 ms e sem violações observáveis.

## 3. Workload

Antes da fase medida, a carga cresce progressivamente:

| Fase | Carga | Duração |
| --- | ---: | ---: |
| aquecimento inicial | 500 pagamentos/s | 60 s |
| aquecimento estável | 1.500 pagamentos/s | 60 s |
| conclusão do trabalho observável do warmup | — | até 120 s |
| fase ativa | 2.100 pagamentos/s | 15 min |
| drain | — | 30 s |

A fase ativa combina:

| Cenário | Participação | Outcome esperado |
| --- | ---: | --- |
| pagamento concluído | 80% | `ACSC`; dinheiro chega ao recebedor |
| saldo insuficiente | 20% | `RJCT / AM04`; nenhum débito é aplicado |

Em cada cenário, 80% do tráfego é concentrado em poucos pares de participantes para criar contenção. Além dos originais, 5% das `pacs.008` e 5% das `pacs.002` elegíveis são repetidas dez segundos depois para exercitar idempotência.

## 4. Medição

HTTP `2xx` confirma somente que o ingresso aceitou a mensagem. O pagamento termina para o benchmark quando o outcome final compatível volta ao pagador pelo Notification Gateway.

| Medida | Definição |
| --- | --- |
| original executado | requisição `pacs.008` realmente iniciada durante a fase ativa |
| throughput sustentado | menor contagem entre todas as janelas contínuas de 1 segundo da fase ativa |
| latência end-to-end | início do HTTP original até a primeira confirmação final compatível no pagador |
| violação observável | outcome ausente, contraditório ou incompatível; resposta causal ou replay selecionado não aceito |

O gerador é open loop: pagamentos atrasados não são reagendados para recuperar a média. Na execução A, 631 slots foram perdidos e permaneceram ausentes do total executado; ainda assim, a pior janela ficou acima do piso.

Duplicatas finais compatíveis são permitidas pela entrega at-least-once. Uma confirmação incompatível continua sendo violação mesmo se uma correta também tiver chegado.

O relatório não relê todos os saldos finais. A idempotência financeira e o rollback conjunto de estado, saldo, auditoria e outbox são verificados pelos testes transacionais descritos em [Corretude do pagamento](topics/payment-correctness.md).

O contrato do gerador — pacing, admissão HTTP/2, warmup, deadlines, replays, coleta e reporting — está em [Metodologia do load test](topics/load-testing.md).

## 5. Ambiente e recursos

Gerador e sistema compartilharam o mesmo host:

| Especificação | Valor |
| --- | --- |
| CPU | Intel Core i7-11390H, 4 núcleos / 8 threads, até 5,0 GHz |
| memória | 16 GB instalados, aproximadamente 15,4 GiB utilizáveis |
| armazenamento | SSD NVMe ADATA IM2P33F3A, 512 GB |
| sistema | Debian 13, kernel `6.12.86+deb13-amd64` |
| Docker / Compose | 29.4.3 / 5.1.3 |

A stack usou uma instância de PostgreSQL, Kafka, Payment Ingress, Payment Processor e Notification Gateway.

| Consumo na fase ativa | Execução A | Execução B |
| --- | ---: | ---: |
| CPU média agregada | 2,094 vCPU | 1,158 vCPU |
| maior amostra completa de CPU | 3,399 vCPU | 2,195 vCPU |
| memória média agregada | 1.824,5 MiB | 1.813,4 MiB |
| maior amostra de memória | 1.994,6 MiB | 1.955,8 MiB |

Uma execução de 15 minutos ocupou aproximadamente 2,63 GB no PostgreSQL e 1,99 GB no Kafka, totalizando 4,62 GB.

Recursos caracterizam o experimento; não eram critérios independentes de aprovação.

## 6. Variação entre as execuções

A execução A consumiu mais CPU e teve operações PostgreSQL mais lentas, embora código, configuração e workload fossem os mesmos.

> Tempos médios por chamada observados pelo PostgreSQL; não representam a latência end-to-end.

| Operação | Execução A | Execução B |
| --- | ---: | ---: |
| insert de pagamentos | 15,086 ms | 5,422 ms |
| insert de auditoria | 4,709 ms | 1,944 ms |
| insert de outbox | 2,629 ms | 0,980 ms |
| lock/leitura da resposta do recebedor | 5,775 ms | 2,253 ms |

A telemetria não isolou uma causa única para essa diferença. Preservar as duas execuções evita apresentar apenas a condição mais favorável; ambas continuaram dentro dos critérios.

## 7. Evidência e reprodução

Os artefatos compactos das qualificações estão versionados em:

```text
docs/performance/evidence/2026-08-29/
├── profile.json
├── execution-plan.json
├── qualification-run-a-sla-report.json
├── qualification-run-b-sla-report.json
└── checksums.sha256
```

Perfil e plano registram a carga efetiva; os relatórios preservam geração, latência, outcomes e replays; os checksums protegem sua integridade. Os relatórios de comparação Go/Rust no mesmo diretório pertencem a outro estudo, não às qualificações finais.

Para executar uma nova observação:

```bash
cd load-test
./prepare-performance-environment.sh --profile mixed-outcomes-2k-15m
./run-load-test.sh --profile mixed-outcomes-2k-15m <run-tag>
```

A preparação recria stack e volumes, aguarda os serviços, provisiona participantes e gera os certificados. Uma nova execução não substitui a evidência preservada e precisa satisfazer novamente todos os critérios.

## 8. Limites

O resultado não demonstra:

* capacidade equivalente à do Pix real ou a uma implantação de produção;
* alta disponibilidade, múltiplas instâncias, multi-região ou Kubernetes;
* comportamento com um cluster Kafka replicado — o teste usou um broker e fator 1;
* isolamento entre gerador e sistema, que compartilharam o host;
* estabilidade por uma hora, 24 horas ou períodos maiores;
* auditoria independente dos saldos finais de todos os pagamentos.

Dentro desse escopo, duas execuções consecutivas sustentam a afirmação promovida pelo projeto: pelo menos 2.000 pagamentos por segundo, p99 abaixo de 1 segundo e nenhum outcome esperado ausente ou contraditório.
