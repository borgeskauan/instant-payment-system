# Performance e evidência

O README resume o principal resultado do projeto: o sistema atingiu a meta de performance em duas execuções consecutivas, sem comprometer a corretude dos pagamentos.

Este documento mostra a evidência por trás desse resultado: o que foi testado, como os números foram medidos e quais são os limites dessa conclusão.

Nas duas qualificações, o sistema manteve pelo menos 2.000 pagamentos por segundo. O maior p99 observado foi de 855 ms, e não houve pagamentos incorretos ou perdidos.

## 1. Critérios de qualificação

Para qualificar, uma execução precisava atender aos três critérios:

| Critério   | Requisito                                         |
| ---------- | ------------------------------------------------- |
| Throughput | pelo menos 2.000 pagamentos originais por segundo |
| Latência   | p99 end-to-end abaixo de 1 segundo                |
| Corretude  | nenhum pagamento incorreto ou perdido             |

A fase ativa dura 15 minutos, com uma carga de 2.100 pagamentos originais por segundo.

O throughput não é medido apenas pela média. Toda janela contínua de um segundo dentro da fase ativa precisa ter pelo menos 2.000 pagamentos iniciados.

Um pagamento original é uma nova transferência criada pela carga de teste. Repetições geram tráfego adicional, mas não entram nessa conta.

A origem dos limites de 2.000 pagamentos por segundo e p99 abaixo de 1 segundo, incluindo sua relação com as referências públicas do Pix usadas no projeto, está descrita no [README](../README.md). Para este experimento, esses são os critérios adotados.

## 2. Resultados

As duas execuções finais usaram:

* o mesmo commit: `1351ea564d0834a66e1b5d99a5e09a1a384cae1b`;
* o mesmo perfil;
* o mesmo plano de execução normalizado;
* a mesma instrumentação.

A worktree estava limpa e o ambiente foi preparado novamente antes de cada execução. Não houve mudança de código, configuração ou procedimento entre uma e outra.

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
| Violações funcionais / de repetição        |                 0 / 0 |                 0 / 0 |

As duas execuções qualificaram de forma independente.

A execução A ficou mais perto dos limites: a pior janela teve 2.017 pagamentos por segundo, apenas 17 acima do requisito, e o p99 chegou a 855 ms.

Na execução B, houve mais margem: 2.079 pagamentos por segundo na pior janela e p99 de 265 ms.

Essa diferença é importante. Dizer apenas que o sistema fez “2.100 pagamentos por segundo em média” esconderia o pior momento da execução A. Pelo mesmo motivo, usar apenas os 265 ms da execução B daria uma visão otimista demais da latência observada.

O resultado das duas execuções, em conjunto, é:

> O sistema manteve pelo menos 2.000 pagamentos por segundo, com p99 abaixo de 1 segundo e sem violações funcionais ou de repetição nas duas qualificações consecutivas.

## 3. O que foi testado

### Fases da execução

A carga sobe gradualmente antes da fase medida:

| Fase                                 |              Carga |   Duração |
| ------------------------------------ | -----------------: | --------: |
| Aquecimento inicial                  |   500 pagamentos/s |      60 s |
| Aquecimento estável                  | 1.500 pagamentos/s |      60 s |
| Espera pela conclusão do aquecimento |                  — | até 120 s |
| Fase ativa                           | 2.100 pagamentos/s |    15 min |
| Encerramento                         |                  — |      30 s |

O aquecimento reduz o impacto da abertura de conexões, do preenchimento de caches e de uma JVM ainda fria sobre a fase principal do teste.

### Cenários e contenção

A carga mistura dois resultados:

| Cenário             | Participação | Resultado esperado          |
| ------------------- | -----------: | --------------------------- |
| Pagamento concluído |          80% | dinheiro chega ao recebedor |
| Saldo insuficiente  |          20% | pagamento é rejeitado       |

Em cada cenário, 80% do tráfego se concentra em um conjunto menor de pares de participantes. Isso cria contenção de propósito, em vez de espalhar os pagamentos de maneira uniforme.

### Repetições

Além dos pagamentos originais, o teste adiciona:

* 5% de repetição dos pedidos de pagamento;
* 5% de repetição das respostas do recebedor.

Essas repetições são enviadas dez segundos depois da mensagem original e exercitam dois casos diferentes:

* **mensagem de entrada repetida:** não pode aplicar o efeito financeiro novamente;
* **confirmação reentregue:** pode aparecer mais de uma vez, desde que continue compatível com as anteriores.

Os 2.100 pagamentos originais por segundo, portanto, não representam todo o tráfego. Respostas, repetições e confirmações acontecem além dessa carga.

## 4. Como medimos

### Latência e corretude

Uma resposta HTTP `2xx` não significa que o pagamento terminou. Ela indica apenas que a entrada do sistema aceitou a mensagem.

Para medir a latência end-to-end, o relógio começa quando a instituição simulada inicia o pedido HTTP original e para quando a confirmação final compatível volta ao remetente.

É esse intervalo que usamos para calcular os percentis do relatório.

A ferramenta de carga sabe de antemão qual resultado cada cenário deveria produzir e confere as confirmações recebidas.

Receber mais de uma confirmação compatível não é um erro. A execução falha na corretude se:

* uma confirmação esperada não chegar;
* o mesmo pagamento receber estados contraditórios;
* o resultado for incompatível com o cenário;
* uma mensagem repetida aplicar novamente o efeito financeiro.

Performance e corretude são verificadas juntas. Uma execução rápida, mas funcionalmente incorreta, não qualifica.

### Throughput

A média sozinha pode esconder quedas de throughput.

Se o sistema inicia 1.500 pagamentos em um segundo e 2.500 no seguinte, a média ainda é de 2.000 pagamentos por segundo. Para este teste, isso não conta como throughput sustentado.

O gerador trabalha em malha aberta (*open loop*). Cada pagamento tem um instante definido para começar. Se esse instante for perdido, o pagamento não é empurrado para uma janela posterior para compensar a média.

O cadenciamento usa intervalos absolutos de 10 ms. Um pagamento só entra na contagem quando sua requisição realmente começa.

Depois da execução, os instantes de início são ordenados e o relatório percorre todas as janelas contínuas de um segundo dentro da fase ativa. A janela com menos pagamentos é reportada como a **menor janela contínua de 1 segundo**.

Assim, uma rajada posterior não consegue compensar uma queda anterior.

Também mostramos separadamente quantos pagamentos foram planejados e quantos realmente começaram.

Na execução A, 631 pagamentos perderam sua janela e não foram iniciados. Eles não foram reagendados. Ainda assim, a pior janela teve 2.017 pagamentos, acima do requisito de 2.000.

### Gerador de carga

O benchmark também depende da capacidade do próprio gerador de produzir a carga declarada.

Ele fica fora do núcleo lógico do sistema, embora rode no mesmo host durante o experimento. Seu trabalho é iniciar os pagamentos nos momentos programados, acompanhar as confirmações e registrar os dados usados no relatório.

Cadenciamento, I/O de rede e geração do relatório são separados. O cálculo das janelas de throughput também acontece depois da execução, fora do caminho crítico da geração de carga.

Há uma limitação aqui: gerador e sistema compartilham o mesmo host e podem interferir um no outro.

Ainda assim, separar essas responsabilidades deixa atrasos do gerador visíveis e evita que trabalho acumulado seja usado em uma rajada posterior para recuperar artificialmente a média.

## 5. Ambiente e recursos

As duas qualificações rodaram localmente, com o gerador de carga e todos os serviços no mesmo host.

CPU, memória e armazenamento não eram critérios para passar ou falhar no teste. Os números desta seção servem para caracterizar o ambiente em que o resultado foi obtido e o consumo observado durante a carga.

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

A memória ficou perto de 1,8 GiB em média nas duas execuções e abaixo de aproximadamente 2 GiB nas maiores amostras.

A CPU variou mais. A execução A consumiu mais CPU e também foi a que apresentou menor margem de throughput e latências maiores.

Os valores máximos da tabela são as maiores amostras coletadas. Não representam um máximo contínuo entre uma coleta e outra.

### Armazenamento

Uma execução de 15 minutos produziu aproximadamente:

| Componente | Armazenamento |
| ---------- | ------------: |
| PostgreSQL |      ~2,63 GB |
| Kafka      |      ~1,99 GB |
| Total      |      ~4,62 GB |

Esse volume inclui o estado persistido pelo fluxo de pagamentos e os dados mantidos pelo Kafka durante o teste.

## 6. Por que as execuções foram diferentes?

A execução A foi claramente mais lenta que a B, mesmo sem mudança de código, configuração ou carga.

Ela também consumiu mais CPU e apresentou tempos médios maiores em operações equivalentes no PostgreSQL:

> Tempo médio de execução por chamada observado pelo PostgreSQL. Esses números não são a latência end-to-end do pagamento.

| Operação no PostgreSQL                | Execução A | Execução B |
| ------------------------------------- | ---------: | ---------: |
| Insert de pagamentos                  |  15,086 ms |   5,422 ms |
| Insert de auditoria                   |   4,709 ms |   1,944 ms |
| Insert de outbox                      |   2,629 ms |   0,980 ms |
| Lock/leitura da resposta do recebedor |   5,775 ms |   2,253 ms |

Os números apontam para uma execução A sob maior pressão, mas não são suficientes para dizer por que isso aconteceu.

Isso não muda a qualificação: a execução A passou nos três critérios, e a execução B repetiu o resultado com margem maior.

Manter as duas como evidência é importante justamente por isso. Mostrar apenas a melhor execução esconderia uma variação que realmente aconteceu durante os testes.

## 7. Evidência e reprodução

### Artefatos preservados

Os artefatos usados para sustentar o resultado estão versionados em:

```text id="2or10w"
docs/performance/evidence/2026-08-29/
├── profile.json
├── execution-plan.json
├── qualification-run-a-sla-report.json
├── qualification-run-b-sla-report.json
├── checksums.sha256
└── manifest.md
```

Cada arquivo tem uma função:

* `profile.json` registra a carga usada;
* `execution-plan.json` registra os parâmetros efetivamente executados;
* os dois relatórios registram geração, cenários, latência, repetições e violações de cada execução;
* `checksums.sha256` permite conferir se os artefatos continuam iguais aos que foram versionados como evidência final.

Logs completos, CSVs intermediários, gravações de profiling, certificados e credenciais ficaram de fora desse conjunto. Eles foram úteis durante a investigação e estabilização, mas não são necessários para verificar o resultado final.

### Como reproduzir

O experimento pode ser executado novamente a partir de um ambiente limpo:

```bash id="qzpcl0"
cd load-test

./prepare-performance-environment.sh --profile mixed-outcomes-2k-15m
./run-load-test.sh --profile mixed-outcomes-2k-15m <run-tag>
```

A preparação recria o estado local necessário, constrói os serviços quando preciso, gera certificados, espera os serviços ficarem disponíveis e provisiona os participantes.

Ela não gera tráfego de pagamento.

Uma nova execução gera uma nova observação. Ela não altera nem substitui as duas execuções já preservadas.

Para qualificar, precisa passar novamente pelos mesmos três critérios: throughput, latência e corretude.

## 8. Limites do resultado

O resultado vale para o experimento descrito aqui. Ele não demonstra:

* capacidade equivalente à do Pix real;
* comportamento de uma implantação de produção;
* alta disponibilidade;
* escala horizontal ou múltiplas instâncias das aplicações;
* operação multi-região;
* comportamento com um cluster Kafka altamente disponível;
* qualificação em Kubernetes;
* estabilidade por uma hora, 24 horas ou períodos maiores.

O Kafka rodou com um único broker e fator de replicação 1.

O gerador e o sistema compartilharam o mesmo host, sem isolamento físico entre quem produz a carga e quem está sendo medido.

E os 15 minutos significam exatamente isso: o sistema sustentou o resultado durante uma janela de 15 minutos. O teste não permite extrapolar esse comportamento para períodos maiores.

Com essas limitações em mente, o resultado sustentado pelas duas execuções é:

> O núcleo manteve pelo menos 2.000 pagamentos por segundo, com p99 abaixo de 1 segundo e sem pagamentos incorretos ou perdidos nas duas qualificações consecutivas.
