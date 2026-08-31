# Performance e evidência

O README apresenta o resultado principal do projeto: o sistema atingiu a meta de performance em duas execuções consecutivas, sem perder a corretude do fluxo de pagamentos.

Aqui a pergunta é mais específica:

> **O que exatamente foi testado, como esses números foram medidos e até onde essa evidência permite concluir?**

## O contrato

A qualificação precisava satisfazer três critérios ao mesmo tempo:

| Critério   |                                Requisito |
| ---------- | ---------------------------------------: |
| Throughput |  pelo menos 2.000 pagamentos por segundo |
| Latência   | 99% dos pagamentos em menos de 1 segundo |
| Corretude  |    nenhum pagamento incorreto ou perdido |

A fase principal dura **15 minutos**.

Durante ela, o gerador oferece **2.100 pagamentos originais por segundo**, deixando uma margem pequena acima do requisito.

O objetivo não é manter apenas uma média de 2.000 pagamentos por segundo. Toda janela contínua de um segundo dentro da fase ativa precisa permanecer acima desse valor.

Um pagamento **original** é uma nova transferência criada pelo workload. Repetições são tráfego adicional e não contam para atingir o piso de throughput.

### De onde vieram os números

Os requisitos públicos usados durante o desenvolvimento inicial da infraestrutura central do Pix traziam uma referência de **2.000 transações por segundo**, com 99% dos pagamentos processados em até **4,6 segundos**.

Os resultados publicados posteriormente pelo Banco Central mostraram que, na prática, o sistema operava com bastante folga em relação ao limite de latência, ficando próximo ou abaixo de **1 segundo** durante grande parte do período observado.

Esses números serviram de referência para o experimento:

> **pelo menos 2.000 pagamentos por segundo, com p99 abaixo de 1 segundo.**

## A carga

A execução não começa diretamente na carga máxima.

Antes da fase medida, o sistema passa por um aquecimento progressivo:

| Fase                                 |              Carga |   Duração |
| ------------------------------------ | -----------------: | --------: |
| Aquecimento inicial                  |   500 pagamentos/s |      60 s |
| Aquecimento estável                  | 1.500 pagamentos/s |      60 s |
| Espera pela conclusão do aquecimento |                  — | até 120 s |
| Fase ativa                           | 2.100 pagamentos/s |    15 min |
| Encerramento                         |                  — |      30 s |

O aquecimento evita que inicialização de conexões, caches e comportamento de uma JVM fria definam o resultado da fase principal.

A carga também não contém apenas pagamentos bem-sucedidos:

| Cenário             | Participação | Resultado esperado          |
| ------------------- | -----------: | --------------------------- |
| Pagamento concluído |          80% | dinheiro chega ao recebedor |
| Saldo insuficiente  |          20% | pagamento é rejeitado       |

Dentro de cada cenário, **80% do tráfego é concentrado em um conjunto menor de pares de participantes**. Isso cria contenção intencional em vez de distribuir todos os pagamentos uniformemente.

Além dos pagamentos originais, o teste acrescenta:

* **5% de repetição dos pedidos de pagamento**;
* **5% de repetição das respostas do recebedor**.

Essas repetições aparecem dez segundos depois da mensagem original e verificam se algo que já foi processado pode aparecer novamente sem produzir o mesmo efeito financeiro outra vez.

Isso significa que os 2.100 pagamentos originais por segundo não representam toda a atividade interna do sistema. Respostas, repetições e confirmações acontecem por cima dessa carga.

## O que conta como um pagamento concluído

Uma resposta HTTP `2xx` não conta como pagamento concluído.

Ela significa apenas que a entrada do sistema aceitou a mensagem.

Para o benchmark, a medição começa quando a instituição simulada inicia o pedido HTTP original e termina apenas quando a confirmação final retorna para quem enviou.

Essa é a latência end-to-end usada no relatório.

A ferramenta de carga conhece previamente o resultado esperado para cada cenário. Quando a confirmação retorna, ela verifica se corresponde ao que deveria ter acontecido.

A entrega das confirmações pode se repetir depois de falhas. Isso é permitido.

O que não é permitido é:

* deixar de receber uma confirmação esperada;
* receber estados contraditórios para o mesmo pagamento;
* receber uma rejeição incompatível com o cenário;
* aplicar novamente o efeito financeiro de uma repetição.

Performance e corretude são verificadas na mesma execução.

Uma execução rápida que produz pagamentos incorretos não qualifica.

## Como o throughput é medido

Uma média pode esconder atraso.

Se o sistema deveria iniciar 2.000 pagamentos durante um segundo, mas inicia apenas 1.500, uma rajada de 2.500 no segundo seguinte ainda produziria uma média de 2.000.

Para este experimento, isso não conta como throughput sustentado.

O gerador trabalha em **open loop**. Cada pagamento possui um momento absoluto em que deve começar. Se perder esse momento, o trabalho não é carregado para uma janela posterior para recuperar a média.

O pacing é dividido em intervalos absolutos de 10 ms.

Um pagamento só é considerado iniciado quando a requisição realmente começa. O gerador registra esse instante para cada pagamento original.

Depois da execução, esses timestamps são ordenados e o relatório procura a menor quantidade de pagamentos presente em qualquer janela contínua de um segundo completamente contida na fase ativa.

É esse valor que aparece como **menor taxa observada**.

Assim, uma rajada posterior não consegue esconder uma queda anterior.

A diferença entre pagamentos planejados e executados também permanece visível.

Na execução A, **631 pagamentos** perderam sua janela temporal e não foram iniciados. Eles não foram deslocados para frente nem escondidos no relatório.

Mesmo assim, a menor janela daquela execução ainda continha **2.017 pagamentos**, portanto ela qualificou.

## O gerador também faz parte do experimento

O benchmark só é válido se a própria ferramenta de carga conseguir produzir o workload que afirma estar produzindo.

Por isso, o gerador fica fora do core. Sua responsabilidade é criar os pagamentos nos momentos planejados, acompanhar as confirmações e preservar os dados necessários para o relatório.

Pacing, I/O de rede e geração do relatório são separados para reduzir a interferência da própria ferramenta sobre a carga.

A análise das janelas contínuas também acontece depois da execução, não durante o caminho crítico de geração.

Isso não elimina toda interferência possível: gerador e stack ainda compartilham o mesmo host.

Mas torna a capacidade do gerador observável e impede que filas internas transformem uma carga atrasada em um resultado aparentemente válido.

## Ambiente de execução

As qualificações foram executadas localmente, com o gerador de carga e toda a stack compartilhando o mesmo host.

### Host

| Especificação       | Valor                                                    |
| ------------------- | -------------------------------------------------------- |
| CPU                 | Intel Core i7-11390H, 4 núcleos / 8 threads, até 5,0 GHz |
| Memória             | 16 GB instalados, aproximadamente 15,4 GiB utilizáveis   |
| Armazenamento       | SSD NVMe ADATA IM2P33F3A, 512 GB                         |
| Sistema operacional | Debian GNU/Linux 13 (Trixie)                             |
| Kernel              | Linux 6.12.86+deb13-amd64                                |
| Docker              | 29.4.3                                                   |
| Docker Compose      | 5.1.3                                                    |

A stack qualificada usa uma instância de cada componente do core:

* PostgreSQL;
* Kafka;
* Payment Ingress;
* Payment Processor;
* Notification Gateway.

Não foi definido um limite formal de CPU, memória ou armazenamento como condição de qualificação.

A intenção era manter a solução em uma ordem de grandeza razoável para execução local, sem depender de uma quantidade desproporcional de hardware, e observar quanto recurso ela realmente consumia sob carga.

Os recursos, portanto, **caracterizam o experimento; não determinam sua aprovação ou reprovação**.

## Resultado final

As duas execuções finais usaram:

* o mesmo commit: `1351ea564d0834a66e1b5d99a5e09a1a384cae1b`;
* o mesmo perfil;
* o mesmo plano de execução normalizado;
* a mesma instrumentação;
* uma worktree limpa;
* uma preparação nova do ambiente antes de cada execução.

Código, configuração e procedimento não mudaram entre elas.

| Resultado                                  |            Execução A |            Execução B |
| ------------------------------------------ | --------------------: | --------------------: |
| Pagamentos planejados / executados         | 1.890.000 / 1.889.369 | 1.890.000 / 1.890.000 |
| Média durante a fase ativa                 |           2.099,299/s |           2.100,000/s |
| Menor janela contínua de 1 segundo         |           **2.017/s** |           **2.079/s** |
| p50 end-to-end                             |                188 ms |                142 ms |
| p95 end-to-end                             |                598 ms |                234 ms |
| p99 end-to-end                             |            **855 ms** |            **265 ms** |
| Maior latência observada                   |              1.578 ms |                693 ms |
| Repetições de pagamento enviadas / aceitas |     100.422 / 100.422 |     100.472 / 100.472 |
| Repetições de status enviadas / aceitas    |       80.326 / 80.326 |       80.373 / 80.373 |
| Violações funcionais / de repetição        |             **0 / 0** |             **0 / 0** |

As duas execuções qualificaram de forma independente.

### Execução A

A execução A foi a condição menos favorável observada.

A menor janela ficou em **2.017 pagamentos por segundo**, apenas 17 acima do requisito, e o p99 chegou a **855 ms**.

Ela também apresentou uma **pressão computacional generalizada**.

A stack consumiu mais CPU e operações de banco semelhantes ficaram substancialmente mais caras em diferentes partes do fluxo, apesar de o volume de trabalho permanecer praticamente o mesmo.

Por exemplo:

| Operação no PostgreSQL                | Execução A | Execução B |
| ------------------------------------- | ---------: | ---------: |
| Insert de pagamentos                  |  15,086 ms |   5,422 ms |
| Insert de auditoria                   |   4,709 ms |   1,944 ms |
| Insert de outbox                      |   2,629 ms |   0,980 ms |
| Lock/leitura da resposta do recebedor |   5,775 ms |   2,253 ms |

A diferença também aparece no consumo agregado de CPU:

| CPU observada          | Execução A | Execução B |
| ---------------------- | ---------: | ---------: |
| Média da stack         | 2,094 vCPU | 1,158 vCPU |
| Maior amostra completa | 3,399 vCPU | 2,195 vCPU |

A telemetria disponível não permite atribuir essa diferença a uma causa única.

Não apareceu uma evidência isolada de locks, I/O ou outro recurso que explicasse sozinho a degradação. Fatores externos ao processo, como scheduling do host ou comportamento da CPU, também não foram isolados.

Por isso, a conclusão preservada é apenas a que os dados sustentam:

> **A execução A exigiu substancialmente mais trabalho computacional para processar praticamente a mesma carga, sem que uma causa única pudesse ser identificada.**

Mesmo nessa condição, ela qualificou em throughput, latência e corretude.

### Execução B

A execução B qualificou com margem maior:

* **2.079 pagamentos por segundo** na menor janela;
* **265 ms** de p99;
* todos os **1.890.000 pagamentos planejados** iniciados;
* zero violações.

Ela confirma que a qualificação é repetível, mas não transforma os 265 ms no único resultado representativo do sistema.

O conjunto das duas execuções sustenta uma conclusão mais conservadora:

> **Nas duas qualificações consecutivas, o sistema permaneceu acima de 2.000 pagamentos por segundo e apresentou p99 entre 265 e 855 ms, sem violações funcionais ou de repetição.**

Manter a execução A é importante justamente por isso. Mostrar somente a melhor execução esconderia uma variação que realmente aconteceu.

## Recursos observados

Recursos não faziam parte dos critérios de qualificação, mas ajudam a dimensionar o custo do resultado.

### CPU e memória

| Consumo observado durante a fase ativa |  Execução A |  Execução B |
| -------------------------------------- | ----------: | ----------: |
| CPU média agregada                     |  2,094 vCPU |  1,158 vCPU |
| Maior amostra completa de CPU          |  3,399 vCPU |  2,195 vCPU |
| Memória média agregada                 | 1.824,5 MiB | 1.813,4 MiB |
| Maior amostra de memória               | 1.994,6 MiB | 1.955,8 MiB |

A memória permaneceu próxima de **1,8 GiB em média** nas duas execuções e abaixo de aproximadamente **2 GiB nas maiores amostras observadas**.

CPU variou muito mais entre as duas runs, acompanhando a pressão computacional observada na execução A.

Os máximos representam as maiores amostras coletadas, não máximos contínuos garantidos entre os intervalos de observação.

### Armazenamento

Uma execução de 15 minutos produziu aproximadamente:

| Componente | Armazenamento |
| ---------- | ------------: |
| PostgreSQL |      ~2,63 GB |
| Kafka      |      ~1,99 GB |
| **Total**  |  **~4,62 GB** |

Esse volume inclui o estado persistido pelo fluxo de pagamentos e os dados mantidos pelo Kafka durante a execução.

O armazenamento é apresentado como característica volumétrica do workload, não como requisito de qualificação.

## Evidência preservada

A evidência compacta das qualificações fica em:

```text
docs/performance/evidence/2026-08-29/
├── profile.json
├── execution-plan.json
├── qualification-run-a-sla-report.json
├── qualification-run-b-sla-report.json
├── checksums.sha256
└── manifest.md
```

O `profile.json` registra o workload usado.

O `execution-plan.json` contém os parâmetros normalizados efetivamente executados.

Os dois relatórios registram geração, cenários, latência, repetições e violações das respectivas runs.

O arquivo `checksums.sha256` permite verificar que os artefatos permanecem iguais aos promovidos como evidência final.

Arquivos grandes de diagnóstico — logs completos, CSVs intermediários, gravações de profiling, certificados e credenciais — não fazem parte da evidência canônica.

Eles foram úteis durante investigação e estabilização, mas não são necessários para verificar a conclusão promovida.

## Reproduzir

A qualificação pode ser executada a partir de um ambiente limpo:

```bash
cd load-test
./prepare-performance-environment.sh --profile mixed-outcomes-2k-15m
./run-load-test.sh --profile mixed-outcomes-2k-15m <run-tag>
```

A preparação recria o estado local necessário para uma nova execução, constrói a stack quando necessário, gera certificados, espera os serviços ficarem disponíveis e provisiona os participantes.

Ela não gera tráfego de pagamento.

Uma nova execução produz **uma nova observação**. Ela não substitui nem modifica a evidência das duas qualificações já versionadas.

## O que esse resultado não prova

A conclusão vale para o experimento que foi executado.

Ela não demonstra:

* capacidade equivalente à do Pix real;
* comportamento de uma implantação de produção;
* alta disponibilidade;
* escala horizontal ou múltiplas instâncias das aplicações;
* operação multi-região;
* comportamento com um cluster Kafka altamente disponível;
* qualificação em Kubernetes;
* estabilidade por uma hora, 24 horas ou períodos maiores.

Kafka foi exercitado com um único broker e fator de replicação 1.

Gerador e stack também compartilharam o mesmo host, portanto não havia isolamento físico entre a produção da carga e o sistema medido.

Os **15 minutos** qualificam exatamente uma janela de 15 minutos de comportamento sustentado. Eles não são uma afirmação sobre estabilidade indefinida.

Dentro desses limites, as duas execuções sustentam a conclusão promovida pelo projeto:

> **O core manteve pelo menos 2.000 pagamentos por segundo, com p99 abaixo de 1 segundo e sem pagamentos incorretos ou perdidos nas duas qualificações consecutivas.**
