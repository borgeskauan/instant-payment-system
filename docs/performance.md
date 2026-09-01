# Performance e evidência

O README resume o resultado principal do projeto: em duas execuções consecutivas, o sistema atingiu a meta de performance e devolveu todos os outcomes esperados sem contradição.

Este documento registra o experimento que sustenta esse resultado: carga aplicada, critérios de qualificação, forma de medição, ambiente e limitações.

Nas duas execuções, o sistema manteve pelo menos 2.000 pagamentos por segundo. O maior p99 observado foi de 855 ms, sem outcome esperado ausente ou contraditório.

## 1. Critérios de qualificação

Uma execução só qualifica se atender aos três critérios:

| Critério             | Requisito                                                                                              |
| -------------------- | ------------------------------------------------------------------------------------------------------ |
| Throughput           | pelo menos 2.000 pagamentos originais por segundo                                                       |
| Latência             | p99 end-to-end abaixo de 1 segundo                                                                      |
| Corretude observável | nenhum outcome esperado ausente ou contraditório e nenhuma falha ao executar as repetições selecionadas |

A fase ativa dura 15 minutos e usa uma carga de 2.100 pagamentos originais por segundo.

O throughput não é avaliado apenas pela média. Toda janela contínua de um segundo dentro da fase ativa precisa conter pelo menos 2.000 pagamentos iniciados.

Um pagamento original é uma nova transferência criada pela carga de teste. Repetições geram tráfego adicional, mas não entram nessa contagem.

A origem dos limites de 2.000 pagamentos por segundo e p99 abaixo de 1 segundo, incluindo a relação com as referências públicas do Pix usadas no projeto, está descrita no [README](../README.md).

## 2. Resultados

As duas execuções finais usaram:

* o mesmo commit: `1351ea564d0834a66e1b5d99a5e09a1a384cae1b`;
* o mesmo perfil;
* o mesmo plano de execução normalizado;
* a mesma instrumentação.

A worktree estava limpa e o ambiente foi preparado novamente antes de cada execução. Não houve mudança de código, configuração ou procedimento entre elas.

| Resultado                                  |            Execução A |            Execução B |
| ------------------------------------------ | --------------------: | --------------------: |
| Pagamentos planejados / executados         | 1.890.000 / 1.889.369 | 1.890.000 / 1.890.000 |
| Média durante a fase ativa                 |           2.099,299/s |           2.100,000/s |
| Menor janela contínua de 1 segundo         |               2.017/s |               2.079/s |
| p50 end-to-end                             |                188 ms |                142 ms |
| p95 end-to-end                             |                598 ms |                234 ms |
| p99 end-to-end                             |                855 ms |                265 ms |
| Maior latência observada                   |              1.578 ms |                693 ms |
| Repetições de pagamento enviadas / aceitas |     100.422 / 100.422 |     100.472 / 100.472 |
| Repetições de status enviadas / aceitas    |       80.326 / 80.326 |       80.373 / 80.373 |
| Outcomes ausentes / contraditórios         |                 0 / 0 |                 0 / 0 |
| Falhas na execução das repetições          |                     0 |                     0 |

As duas execuções qualificaram de forma independente.

A execução A ficou mais próxima dos limites: a pior janela teve 2.017 pagamentos por segundo e o p99 chegou a 855 ms.

A execução B teve mais margem, com 2.079 pagamentos por segundo na pior janela e p99 de 265 ms.

Por isso, o resultado não é resumido apenas pela média de 2.100 pagamentos por segundo nem pela melhor latência observada. Os critérios consideram a pior janela de throughput e o comportamento das duas execuções.

> O sistema manteve pelo menos 2.000 pagamentos por segundo, com p99 abaixo de 1 segundo, todos os outcomes esperados presentes e compatíveis e todas as repetições selecionadas executadas com sucesso nas duas qualificações consecutivas.

## 3. O que foi testado

### Fases da execução

A carga aumenta gradualmente antes da fase medida:

| Fase                                 |              Carga |   Duração |
| ------------------------------------ | -----------------: | --------: |
| Aquecimento inicial                  |   500 pagamentos/s |      60 s |
| Aquecimento estável                  | 1.500 pagamentos/s |      60 s |
| Espera pela conclusão do aquecimento |                  — | até 120 s |
| Fase ativa                           | 2.100 pagamentos/s |    15 min |
| Encerramento                         |                  — |      30 s |

O aquecimento reduz o impacto de abertura de conexões, preenchimento de caches e aquecimento da JVM sobre a fase principal.

### Cenários e contenção

A carga combina dois resultados:

| Cenário             | Participação | Resultado esperado          |
| ------------------- | -----------: | --------------------------- |
| Pagamento concluído |          80% | dinheiro chega ao recebedor |
| Saldo insuficiente  |          20% | pagamento é rejeitado       |

Em cada cenário, 80% do tráfego é concentrado em um conjunto menor de pares de participantes para criar contenção. A distribuição, portanto, não é uniforme.

### Repetições

Além dos pagamentos originais, o teste envia:

* 5% de repetição dos pedidos de pagamento;
* 5% de repetição das respostas do recebedor.

As repetições são enviadas dez segundos depois da mensagem original e exercitam a idempotência das duas entradas do fluxo:

* **`pacs.008` repetida:** não pode reservar o valor novamente;
* **`pacs.002` repetida:** não pode creditar o recebedor nem devolver a reserva novamente.

Separadamente, a entrega de confirmações ao pagador é at-least-once. Uma confirmação final compatível pode chegar mais de uma vez; estados incompatíveis para o mesmo pagamento continuam sendo erro.

Os 2.100 pagamentos originais por segundo não representam todo o tráfego produzido durante o teste. Respostas, repetições e confirmações acontecem além dessa carga.

## 4. Como medimos

### Latência e corretude

Uma resposta HTTP `2xx` indica apenas que a entrada do sistema aceitou a mensagem. Ela não significa que o pagamento terminou.

A latência end-to-end começa quando a instituição simulada inicia o pedido HTTP original e termina quando a confirmação final compatível retorna ao remetente.

Os percentis do relatório são calculados sobre esse intervalo.

A ferramenta de carga conhece previamente o resultado esperado de cada cenário e valida as confirmações recebidas.

Receber mais de uma confirmação compatível não é considerado erro. A execução falha na corretude observável se:

* uma confirmação esperada não chegar;
* o mesmo pagamento receber estados contraditórios;
* o resultado for incompatível com o cenário;
* uma repetição selecionada não for executada ou não for aceita pelo ingresso.

O relatório não relê os saldos finais do PostgreSQL. Portanto, ele exercita a idempotência sob carga, mas não prova sozinho que uma repetição deixou de aplicar um segundo efeito financeiro. Essa invariante é verificada diretamente pelos [testes concorrentes do SPI](../spi/src/test/java/br/kauan/spi/domain/services/ConcurrentParticipantBalanceIntegrationTest.java), que conferem no banco que requisições idênticas reservam, creditam ou devolvem o valor exatamente uma vez. Os [testes da outbox transacional](../spi/src/test/java/br/kauan/spi/domain/services/TransactionalOutboxRollbackIntegrationTest.java) verificam separadamente que uma falha de persistência reverte estado, saldo, auditoria e obrigação de notificar juntos.

Uma execução precisa atender simultaneamente aos critérios de performance e corretude observável. A conclusão mais ampla sobre corretude financeira combina essa observação end-to-end com os testes transacionais e concorrentes do core.

### Throughput

A média não é suficiente para demonstrar throughput sustentado.

Se o sistema inicia 1.500 pagamentos em um segundo e 2.500 no seguinte, a média é de 2.000 pagamentos por segundo, mas houve uma janela abaixo do requisito.

O gerador trabalha em malha aberta (*open loop*). Cada pagamento possui um instante programado para começar. Se esse instante for perdido, o pagamento não é deslocado para uma janela posterior para compensar a média.

O cadenciamento usa intervalos absolutos de 10 ms. Um pagamento entra na contagem apenas quando sua requisição realmente começa.

Depois da execução, os instantes de início são ordenados e o relatório percorre todas as janelas contínuas de um segundo dentro da fase ativa. A menor delas é reportada como **menor janela contínua de 1 segundo**.

Uma rajada posterior, portanto, não compensa uma queda anterior.

O relatório também separa pagamentos planejados de pagamentos efetivamente iniciados.

Na execução A, 631 pagamentos perderam sua janela e não foram iniciados. Eles não foram reagendados. Mesmo assim, a pior janela ficou em 2.017 pagamentos, acima do requisito de 2.000.

### Gerador de carga

A validade do benchmark também depende da capacidade do gerador de produzir a carga declarada.

O gerador fica fora do núcleo lógico do sistema, embora rode no mesmo host durante o experimento. Ele inicia os pagamentos nos instantes programados, acompanha as confirmações e registra os dados usados pelo relatório.

Cadenciamento, I/O de rede e geração do relatório são separados. O cálculo das janelas de throughput acontece depois da execução, fora do caminho crítico da geração de carga.

Gerador e sistema compartilham o mesmo host e, portanto, podem interferir um no outro.

O modelo de malha aberta e a contabilização dos pagamentos efetivamente iniciados tornam atrasos do próprio gerador visíveis, em vez de permitir que trabalho acumulado seja disparado depois para recuperar a média.

## 5. Ambiente e recursos

As duas qualificações foram executadas localmente, com o gerador de carga e todos os serviços no mesmo host.

CPU, memória e armazenamento não fazem parte dos critérios de aprovação. Os valores abaixo caracterizam o ambiente e o consumo observado durante o experimento.

### Ambiente

| Especificação       | Valor                                                    |
| ------------------- | -------------------------------------------------------- |
| CPU                 | Intel Core i7-11390H, 4 núcleos / 8 threads, até 5,0 GHz |
| Memória             | 16 GB instalados, aproximadamente 15,4 GiB utilizáveis   |
| Armazenamento       | SSD NVMe ADATA IM2P33F3A, 512 GB                         |
| Sistema operacional | Debian GNU/Linux 13 (Trixie)                             |
| Kernel              | Linux 6.12.86+deb13-amd64                                |
| Docker              | 29.4.3                                                   |
| Docker Compose      | 5.1.3                                                    |

A configuração testada usa uma instância de cada componente:

* PostgreSQL;
* Kafka;
* Payment Ingress;
* Payment Processor;
* Notification Gateway.

### CPU e memória

| Consumo durante a fase ativa  |  Execução A |  Execução B |
| ----------------------------- | ----------: | ----------: |
| CPU média agregada            |  2,094 vCPU |  1,158 vCPU |
| Maior amostra completa de CPU |  3,399 vCPU |  2,195 vCPU |
| Memória média agregada        | 1.824,5 MiB | 1.813,4 MiB |
| Maior amostra de memória      | 1.994,6 MiB | 1.955,8 MiB |

A memória ficou próxima de 1,8 GiB em média nas duas execuções e abaixo de aproximadamente 2 GiB nas maiores amostras.

A CPU variou mais. A execução A consumiu mais CPU e também apresentou menor margem de throughput e latências maiores.

Os valores máximos representam as maiores amostras coletadas, não um máximo contínuo entre coletas.

### Armazenamento

Uma execução de 15 minutos produziu aproximadamente:

| Componente | Armazenamento |
| ---------- | ------------: |
| PostgreSQL |      ~2,63 GB |
| Kafka      |      ~1,99 GB |
| Total      |      ~4,62 GB |

Esse volume inclui o estado persistido pelo fluxo de pagamentos e os dados mantidos pelo Kafka durante o teste.

## 6. Diferença entre as execuções

A execução A foi mais lenta que a B apesar de usar o mesmo código, configuração e carga.

Ela também consumiu mais CPU e apresentou tempos médios maiores em operações equivalentes no PostgreSQL.

> Os valores abaixo são tempos médios de execução por chamada observados pelo PostgreSQL. Eles não representam a latência end-to-end do pagamento.

| Operação no PostgreSQL                | Execução A | Execução B |
| ------------------------------------- | ---------: | ---------: |
| Insert de pagamentos                  |  15,086 ms |   5,422 ms |
| Insert de auditoria                   |   4,709 ms |   1,944 ms |
| Insert de outbox                      |   2,629 ms |   0,980 ms |
| Lock/leitura da resposta do recebedor |   5,775 ms |   2,253 ms |

Os dados mostram que a execução A operou sob maior pressão, mas não permitem identificar a causa dessa diferença.

Isso não afeta o resultado da qualificação: as duas execuções passaram nos três critérios. Preservar ambas evita que a evidência represente apenas a execução com melhor comportamento.

## 7. Evidência e reprodução

### Artefatos preservados

Os artefatos usados para sustentar o resultado estão versionados em:

```text
docs/performance/evidence/2026-08-29/

├── profile.json
├── execution-plan.json
├── qualification-run-a-sla-report.json
├── qualification-run-b-sla-report.json
├── checksums.sha256
└── manifest.md
```

Cada arquivo registra uma parte do experimento:

* `profile.json`: perfil de carga;
* `execution-plan.json`: parâmetros efetivamente executados;
* `qualification-run-a-sla-report.json` e `qualification-run-b-sla-report.json`: geração de carga, cenários, latência, outcomes e execução das repetições em cada run;
* `checksums.sha256`: integridade dos artefatos preservados.

Logs completos, CSVs intermediários, gravações de profiling, certificados e credenciais não fazem parte desse conjunto. Eles foram usados durante investigação e estabilização, mas não são necessários para verificar o resultado final.

### Como reproduzir

O experimento pode ser executado novamente a partir de um ambiente limpo:

```bash
cd load-test

./prepare-performance-environment.sh --profile mixed-outcomes-2k-15m

./run-load-test.sh --profile mixed-outcomes-2k-15m <run-tag>
```

A preparação recria o estado local necessário, constrói os serviços quando necessário, gera certificados, aguarda a disponibilidade dos serviços e provisiona os participantes.

Ela não gera tráfego de pagamento.

Uma nova execução produz uma nova observação e não substitui as duas execuções preservadas.

Para qualificar, a nova execução precisa atender novamente aos mesmos critérios de throughput, latência e corretude.

## 8. Limites do resultado

O resultado vale para o experimento descrito neste documento. Ele não demonstra:

* capacidade equivalente à do Pix real;
* comportamento de uma implantação de produção;
* alta disponibilidade;
* escala horizontal ou múltiplas instâncias das aplicações;
* operação multi-região;
* comportamento com um cluster Kafka altamente disponível;
* qualificação em Kubernetes;
* estabilidade por uma hora, 24 horas ou períodos maiores;
* uma auditoria independente dos saldos finais de todos os pagamentos da run.

O Kafka foi executado com um único broker e fator de replicação 1.

O gerador e o sistema compartilharam o mesmo host, sem isolamento físico entre quem produz a carga e quem está sendo medido.

A fase medida durou 15 minutos. O resultado não deve ser extrapolado para períodos maiores sem novos testes.

Dentro desse escopo, as duas execuções demonstraram pelo menos 2.000 pagamentos por segundo, p99 abaixo de 1 segundo, nenhum outcome esperado ausente ou contraditório e nenhuma falha na execução das repetições selecionadas.
