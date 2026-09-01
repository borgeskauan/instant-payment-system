# Performance e evidência

O README apresenta o resultado principal do projeto: o sistema atingiu a meta de performance em duas execuções consecutivas, sem perder a corretude do fluxo de pagamentos.

Este documento responde a uma pergunta mais específica:

> O que exatamente foi testado, como os números foram medidos e até onde essa evidência permite concluir?

## Critérios de qualificação

Uma execução só qualificava se atendesse aos três critérios ao mesmo tempo:

| Critério   | Requisito                                                   |
| ---------- | ----------------------------------------------------------- |
| Throughput | pelo menos 2.000 pagamentos originais por segundo          |
| Latência   | percentil 99 (p99) end-to-end abaixo de 1 segundo          |
| Corretude  | nenhum pagamento incorreto ou perdido                      |

A fase ativa dura 15 minutos. Durante ela, o gerador oferece 2.100 pagamentos originais por segundo, deixando uma margem pequena acima do requisito.

O critério de throughput não considera apenas a média da execução. Toda janela contínua de um segundo completamente contida na fase ativa precisa permanecer em pelo menos 2.000 pagamentos iniciados.

Um pagamento original é uma nova transferência criada pela carga de teste. Repetições são tráfego adicional e não contam para atingir esse piso.

A origem dos limites de 2.000 pagamentos por segundo e p99 abaixo de 1 segundo, assim como sua relação com as referências públicas do Pix usadas durante o projeto, é discutida no [README](../README.md). Aqui, esses valores são tratados como o contrato do experimento.

## Resultado das qualificações

As duas execuções finais usaram:

* o mesmo commit: `1351ea564d0834a66e1b5d99a5e09a1a384cae1b`;
* o mesmo perfil;
* o mesmo plano de execução normalizado;
* a mesma instrumentação;
* uma worktree limpa;
* uma preparação nova do ambiente antes de cada execução.

Código, configuração e procedimento não mudaram entre elas.

| Resultado                                      | Execução A            | Execução B            |
| ---------------------------------------------- | --------------------: | --------------------: |
| Pagamentos planejados / executados             | 1.890.000 / 1.889.369 | 1.890.000 / 1.890.000 |
| Média durante a fase ativa                     |           2.099,299/s |           2.100,000/s |
| Menor janela contínua de 1 segundo             |               2.017/s |               2.079/s |
| p50 end-to-end                                 |                188 ms |                142 ms |
| p95 end-to-end                                 |                598 ms |                234 ms |
| p99 end-to-end                                 |                855 ms |                265 ms |
| Maior latência observada                       |              1.578 ms |                693 ms |
| Repetições de pagamento enviadas / aceitas     |   100.422 / 100.422   |   100.472 / 100.472   |
| Repetições de status enviadas / aceitas        |    80.326 / 80.326    |    80.373 / 80.373    |
| Violações funcionais / de repetição            |                 0 / 0 |                 0 / 0 |

As duas execuções qualificaram de forma independente.

A execução A foi a condição menos favorável observada: sua menor janela ficou em 2.017 pagamentos por segundo, apenas 17 acima do requisito, e o p99 chegou a 855 ms. A execução B teve margem maior, com 2.079 pagamentos por segundo na menor janela e p99 de 265 ms.

Por isso, o resultado não deve ser resumido apenas como “2.100 pagamentos por segundo em média”, nem o p99 de 265 ms deve ser tratado isoladamente como representativo.

O que as duas qualificações sustentam é:

> Nas duas execuções consecutivas, o sistema permaneceu acima de 2.000 pagamentos por segundo, com p99 entre 265 e 855 ms e sem violações funcionais ou de repetição.

## Carga de teste

A execução não começa diretamente na carga máxima.

Antes da fase medida, o sistema passa por um aquecimento progressivo:

| Fase                                  | Carga                | Duração   |
| ------------------------------------- | -------------------- | --------- |
| Aquecimento inicial                   | 500 pagamentos/s     | 60 s      |
| Aquecimento estável                   | 1.500 pagamentos/s   | 60 s      |
| Espera pela conclusão do aquecimento  | —                    | até 120 s |
| Fase ativa                            | 2.100 pagamentos/s   | 15 min    |
| Encerramento                          | —                    | 30 s      |

O aquecimento reduz a influência da inicialização de conexões, caches e do comportamento de uma JVM fria sobre a fase principal do experimento.

A carga também não contém apenas pagamentos bem-sucedidos:

| Cenário              | Participação | Resultado esperado             |
| -------------------- | -----------: | ------------------------------ |
| Pagamento concluído  |          80% | dinheiro chega ao recebedor    |
| Saldo insuficiente   |          20% | pagamento é rejeitado          |

Dentro de cada cenário, 80% do tráfego é concentrado em um conjunto menor de pares de participantes. Isso cria contenção intencional em vez de distribuir todos os pagamentos uniformemente.

Além dos pagamentos originais, o teste acrescenta:

* 5% de repetição dos pedidos de pagamento;
* 5% de repetição das respostas do recebedor.

Essas repetições são enviadas dez segundos depois da mensagem original. O objetivo é verificar se uma mensagem já processada pode aparecer novamente sem produzir o mesmo efeito financeiro outra vez.

Há duas formas de repetição no experimento e elas têm significados diferentes:

* repetição de uma mensagem de entrada: é introduzida deliberadamente para testar idempotência e não pode reaplicar o efeito financeiro;
* reentrega de uma confirmação: pode acontecer depois de falhas e é permitida, desde que as confirmações sejam compatíveis entre si.

Portanto, os 2.100 pagamentos originais por segundo não representam toda a atividade do sistema. Respostas, repetições e confirmações acontecem adicionalmente a essa carga.

## O que conta como um pagamento concluído

Uma resposta HTTP `2xx` não conta como pagamento concluído.

Ela significa apenas que a entrada do sistema aceitou a mensagem.

Para o benchmark, a latência começa quando a instituição simulada inicia o pedido HTTP original e termina somente quando a confirmação final compatível retorna para quem enviou.

Essa é a latência end-to-end usada para calcular os percentis apresentados no relatório.

A ferramenta de carga conhece previamente o resultado esperado de cada cenário. Quando uma confirmação retorna, ela verifica se o resultado corresponde ao que deveria ter acontecido.

Receber mais de uma confirmação compatível não é, por si só, uma violação. O que não é permitido é:

* deixar de receber uma confirmação esperada;
* receber estados contraditórios para o mesmo pagamento;
* receber uma rejeição incompatível com o cenário;
* aplicar novamente o efeito financeiro de uma mensagem repetida.

Performance e corretude são verificadas na mesma execução.

Uma execução rápida que produz pagamentos incorretos não qualifica.

## Como o throughput é medido

Uma média pode esconder períodos de carga insuficiente.

Se o sistema deveria iniciar 2.000 pagamentos durante um segundo, mas inicia apenas 1.500, uma rajada de 2.500 no segundo seguinte ainda produziria uma média de 2.000 pagamentos por segundo.

Para este experimento, isso não conta como throughput sustentado.

O gerador trabalha em malha aberta (open loop). Cada pagamento possui um momento absoluto em que deve começar. Se esse momento for perdido, o trabalho não é transferido para uma janela posterior apenas para recuperar a média.

O cadenciamento é dividido em intervalos absolutos de 10 ms.

Um pagamento só é considerado iniciado quando a requisição realmente começa. O gerador registra esse instante para cada pagamento original.

Depois da execução, os instantes registrados são ordenados e o relatório calcula quantos pagamentos aparecem em cada janela contínua de um segundo completamente contida na fase ativa.

O menor desses valores é a menor janela contínua de 1 segundo apresentada no relatório.

Assim, uma rajada posterior não consegue esconder uma queda anterior.

A diferença entre pagamentos planejados e executados também permanece explícita.

Na execução A, 631 pagamentos perderam sua janela temporal e não foram iniciados. Eles não foram deslocados para frente nem ocultados pelo cálculo da média.

Mesmo assim, a menor janela contínua daquela execução ainda continha 2.017 pagamentos, portanto a execução qualificou.

## Como validamos o gerador de carga

O benchmark só é válido se a própria ferramenta de carga conseguir produzir a carga que afirma estar produzindo.

Por isso, o gerador é externo ao núcleo lógico do sistema, embora compartilhe o mesmo host durante o experimento. Sua responsabilidade é iniciar os pagamentos nos momentos planejados, acompanhar as confirmações e preservar os dados necessários para o relatório.

Cadenciamento, I/O de rede e geração do relatório são separados para reduzir a interferência da própria ferramenta sobre a carga.

A análise das janelas contínuas também acontece depois da execução, fora do caminho crítico de geração.

Isso não elimina toda interferência possível, já que gerador e sistema compartilham o mesmo host. A separação, porém, torna a capacidade do gerador observável e evita que filas internas transformem uma carga atrasada em um resultado aparentemente válido.

## Ambiente de execução

As qualificações foram executadas localmente, com o gerador de carga e todo o conjunto de serviços compartilhando o mesmo host.

### Host

| Especificação       | Valor                                                        |
| ------------------- | ------------------------------------------------------------ |
| CPU                 | Intel Core i7-11390H, 4 núcleos / 8 threads, até 5,0 GHz     |
| Memória             | 16 GB instalados, aproximadamente 15,4 GiB utilizáveis       |
| Armazenamento       | SSD NVMe ADATA IM2P33F3A, 512 GB                             |
| Sistema operacional | Debian GNU/Linux 13 (Trixie)                                  |
| Kernel              | Linux 6.12.86+deb13-amd64                                     |
| Docker              | 29.4.3                                                       |
| Docker Compose      | 5.1.3                                                        |

O núcleo qualificado usa uma instância de cada componente:

* PostgreSQL;
* Kafka;
* Payment Ingress;
* Payment Processor;
* Notification Gateway.

Não foi definido um limite formal de CPU, memória ou armazenamento como condição de qualificação.

A intenção era manter a solução em uma ordem de grandeza razoável para execução local, sem depender de uma quantidade desproporcional de hardware, e observar quanto recurso ela realmente consumia sob carga.

Os recursos, portanto, caracterizam o experimento; não determinam sua aprovação ou reprovação.

## Recursos observados

### CPU e memória

| Consumo observado durante a fase ativa | Execução A  | Execução B  |
| --------------------------------------- | ----------: | ----------: |
| CPU média agregada                      | 2,094 vCPU  | 1,158 vCPU  |
| Maior amostra completa de CPU           | 3,399 vCPU  | 2,195 vCPU  |
| Memória média agregada                  | 1.824,5 MiB | 1.813,4 MiB |
| Maior amostra de memória                | 1.994,6 MiB | 1.955,8 MiB |

A memória permaneceu próxima de 1,8 GiB em média nas duas execuções e abaixo de aproximadamente 2 GiB nas maiores amostras observadas.

O consumo de CPU variou muito mais entre as duas execuções, acompanhando a maior pressão computacional observada na execução A.

Os máximos da tabela representam as maiores amostras coletadas. Eles não estabelecem um máximo contínuo entre os intervalos de observação.

### Armazenamento

Uma execução de 15 minutos produziu aproximadamente:

| Componente | Armazenamento |
| ---------- | ------------: |
| PostgreSQL |      ~2,63 GB |
| Kafka      |      ~1,99 GB |
| Total      |      ~4,62 GB |

Esse volume inclui o estado persistido pelo fluxo de pagamentos e os dados mantidos pelo Kafka durante a execução.

O armazenamento é apresentado como característica volumétrica da carga de teste, não como requisito de qualificação.

## Como interpretar as duas execuções

A execução A foi materialmente mais lenta do que a execução B sem mudança de código, configuração ou carga.

Além do p99 mais alto e da margem menor de throughput, ela apresentou maior consumo de CPU. A média agregada do conjunto de serviços foi de 2,094 vCPU na execução A contra 1,158 vCPU na execução B.

Os dados de diagnóstico também mostraram tempos maiores para classes equivalentes de operações no PostgreSQL.

> Tempo médio de execução por chamada, observado pelo PostgreSQL. Não representa a latência end-to-end do pagamento.

| Operação no PostgreSQL                    | Execução A | Execução B |
| ----------------------------------------- | ---------: | ---------: |
| Insert de pagamentos                      |  15,086 ms |   5,422 ms |
| Insert de auditoria                       |   4,709 ms |   1,944 ms |
| Insert de outbox                          |   2,629 ms |   0,980 ms |
| Lock/leitura da resposta do recebedor     |   5,775 ms |   2,253 ms |

Os tempos médios por chamada foram maiores na execução A para operações equivalentes. Isso é consistente com a maior pressão observada nessa execução, mas não permite atribuir a diferença entre A e B a uma causa específica.

Mesmo nessa condição, a execução A qualificou em throughput, latência e corretude.

A execução B qualificou com margem maior: iniciou todos os 1.890.000 pagamentos planejados, manteve 2.079 pagamentos por segundo na menor janela, apresentou p99 de 265 ms e teve zero violações.

Ela demonstra que a qualificação pôde ser repetida nas mesmas condições documentadas, mas não transforma os 265 ms no único resultado representativo.

Preservar a execução A é importante justamente por isso. Mostrar apenas a execução mais favorável esconderia uma variação que realmente ocorreu durante a qualificação.

## Evidência preservada

A evidência compacta das duas qualificações fica em:

```text
docs/performance/evidence/2026-08-29/
├── profile.json
├── execution-plan.json
├── qualification-run-a-sla-report.json
├── qualification-run-b-sla-report.json
├── checksums.sha256
└── manifest.md
```

O `profile.json` registra a carga de teste usada.

O `execution-plan.json` contém os parâmetros normalizados efetivamente executados.

Os dois relatórios registram geração, cenários, latência, repetições e violações das respectivas execuções.

O arquivo `checksums.sha256` permite verificar que os artefatos permanecem iguais aos promovidos como evidência final.

Arquivos grandes de diagnóstico — como logs completos, CSVs intermediários, gravações de profiling, certificados e credenciais — não fazem parte da evidência canônica.

Eles foram úteis durante investigação e estabilização, mas não são necessários para verificar a conclusão promovida.

## Reproduzir o experimento

A qualificação pode ser executada novamente a partir de um ambiente limpo:

```bash
cd load-test
./prepare-performance-environment.sh --profile mixed-outcomes-2k-15m
./run-load-test.sh --profile mixed-outcomes-2k-15m <run-tag>
```

A preparação recria o estado local necessário para uma nova execução, constrói o conjunto de serviços quando necessário, gera certificados, espera os serviços ficarem disponíveis e provisiona os participantes.

Ela não gera tráfego de pagamento.

Uma nova execução produz uma nova observação. Ela não substitui nem modifica a evidência das duas qualificações já versionadas.

Da mesma forma, a conclusão de que uma nova execução qualificou depende dos resultados produzidos: throughput, latência e corretude ainda precisam ser comparados aos critérios definidos no início deste documento.

## Limites da evidência

A conclusão vale para o experimento que foi efetivamente executado.

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

O gerador e o sistema também compartilharam o mesmo host, portanto não havia isolamento físico entre a produção da carga e o sistema medido.

Os 15 minutos qualificam exatamente uma janela de 15 minutos de comportamento sustentado. Eles não constituem uma afirmação sobre estabilidade indefinida.

Dentro desses limites, as duas execuções sustentam a conclusão promovida pelo projeto:

> O núcleo manteve pelo menos 2.000 pagamentos por segundo, com p99 abaixo de 1 segundo e sem pagamentos incorretos ou perdidos nas duas qualificações consecutivas.
