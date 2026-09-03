# Performance e evidência

A pergunta do benchmark era direta:

> O sistema consegue sustentar pelo menos 2.000 pagamentos por segundo, terminar 99% deles em menos de um segundo e ainda devolver todos os resultados corretos?

Em duas execuções consecutivas de 15 minutos, a resposta foi sim.

Este documento mostra o que foi executado, os resultados observados, o computador usado e o escopo da medição. O funcionamento detalhado do gerador está em [Como o teste de carga funciona](topics/load-testing.md).

## O que precisava ser verdade

Não bastava atingir uma média alta. Cada execução precisava cumprir os três critérios ao mesmo tempo:

| Critério | Requisito |
| --- | --- |
| throughput | pelo menos 2.000 pagamentos novos em toda janela contínua de 1 segundo |
| latência | p99 end-to-end abaixo de 1 segundo |
| resultados | nenhuma confirmação esperada ausente ou contraditória e nenhuma falha nas repetições selecionadas |

Durante a fase medida, o gerador tenta iniciar 2.100 pagamentos novos por segundo. Respostas do recebedor, confirmações e mensagens repetidas aumentam o trabalho do sistema, mas não contam para atingir a meta.

A origem dessas metas e sua relação com referências públicas do Pix está no [README](../README.md).

## O que aconteceu

As duas execuções usaram o commit `1351ea564d0834a66e1b5d99a5e09a1a384cae1b`, a mesma configuração e o mesmo plano de carga. O repositório não possuía alterações locais, e todo o ambiente foi recriado antes de cada execução.

| Resultado | Execução A | Execução B |
| --- | ---: | ---: |
| pagamentos planejados / iniciados | 1.890.000 / 1.889.369 | 1.890.000 / 1.890.000 |
| média durante a fase medida | 2.099,299/s | 2.100,000/s |
| menor janela contínua de 1 segundo | 2.017/s | 2.079/s |
| p50 end-to-end | 188 ms | 142 ms |
| p95 end-to-end | 598 ms | 234 ms |
| p99 end-to-end | 855 ms | 265 ms |
| maior latência observada | 1.578 ms | 693 ms |
| repetições de pagamento enviadas / aceitas | 100.422 / 100.422 | 100.472 / 100.472 |
| respostas repetidas enviadas / aceitas | 80.326 / 80.326 | 80.373 / 80.373 |
| confirmações ausentes / contraditórias | 0 / 0 | 0 / 0 |
| falhas nas repetições | 0 | 0 |

Os percentis acima combinam os dois cenários. Como uma rejeição por saldo insuficiente termina sem esperar a decisão do recebedor, os relatórios também preservam a latência de cada caminho separadamente:

### Execução A

| Latência | Todos os pagamentos | Pagamento concluído | Saldo insuficiente |
| --- | ---: | ---: | ---: |
| p50 | 188 ms | 203 ms | 107 ms |
| p95 | 598 ms | 624 ms | 457 ms |
| p99 | 855 ms | 879 ms | 698 ms |

### Execução B

| Latência | Todos os pagamentos | Pagamento concluído | Saldo insuficiente |
| --- | ---: | ---: | ---: |
| p50 | 142 ms | 158 ms | 77 ms |
| p95 | 234 ms | 237 ms | 124 ms |
| p99 | 265 ms | 272 ms | 151 ms |

O caminho concluído foi mais caro nas duas execuções, como esperado. A execução A foi mais lenta nos dois cenários. Portanto, a diferença entre A e B não veio apenas da etapa adicional de decisão do recebedor. Ela também não foi causada pela proporção de rejeições rápidas.

Cada execução cumpriu os três critérios por conta própria.

A execução A foi a menos favorável: sua pior janela ficou apenas 17 pagamentos acima do piso, e o p99 chegou a 855 ms. A execução B terminou com margem maior.

Por isso, mostrar somente a média de 2.100 pagamentos por segundo ou o melhor p99 deixaria uma impressão incompleta. O que as duas execuções sustentam é:

> O sistema permaneceu acima de 2.000 pagamentos por segundo nas duas execuções. Em uma delas, 99% dos pagamentos terminaram em até 855 ms; na outra, em até 265 ms. Nenhuma confirmação esperada ficou ausente ou apresentou contradição.

## Como era a carga

Antes dos 15 minutos principais, a carga cresce em duas etapas. Esse aquecimento reduz a influência da criação inicial de conexões, caches e JVMs:

| Fase | Carga | Duração |
| --- | ---: | ---: |
| aquecimento inicial | 500 pagamentos/s | 60 s |
| aquecimento estável | 1.500 pagamentos/s | 60 s |
| espera pela conclusão do aquecimento | — | até 120 s |
| fase medida | 2.100 pagamentos/s | 15 min |
| encerramento | — | 30 s |

Nem todo pagamento deveria ser aceito:

| Cenário | Participação | Resultado esperado |
| --- | ---: | --- |
| pagamento concluído | 80% | dinheiro chega ao recebedor |
| saldo insuficiente | 20% | pagamento é rejeitado sem débito |

Em cada cenário, 80% do tráfego se concentra em poucos pares de instituições. O teste, portanto, cria disputas intencionais pelo mesmo saldo em vez de distribuir tudo uniformemente.

Além dos pagamentos novos, o teste envia novamente 5% dos pedidos e 5% das respostas elegíveis dez segundos depois. Isso verifica se a mesma mensagem pode reaparecer sem mover o dinheiro outra vez.

## Quando um pagamento conta

Uma resposta HTTP bem-sucedida significa apenas que a entrada recebeu a mensagem. Para o benchmark, o pagamento só termina quando a confirmação final correta volta ao pagador.

O throughput também não é calculado apenas pela média. O relatório examina todas as janelas contínuas de um segundo e preserva a menor contagem encontrada. Uma rajada posterior não consegue esconder um período abaixo da meta.

Na execução A, 631 pagamentos perderam a janela planejada e não foram iniciados. Eles permaneceram fora do total; mesmo assim, a pior janela ainda continha 2.017 pagamentos.

O gerador verifica as confirmações que recebe e as repetições que executa. Ele não relê todos os saldos finais do PostgreSQL. As garantias financeiras mais profundas são verificadas pelos testes descritos em [Corretude do pagamento](topics/payment-correctness.md).

## Onde o teste rodou

O gerador e todos os serviços compartilharam o mesmo computador:

| Especificação | Valor |
| --- | --- |
| CPU | Intel Core i7-11390H, 4 núcleos / 8 threads, até 5,0 GHz |
| memória | 16 GB instalados, aproximadamente 15,4 GiB utilizáveis |
| armazenamento | SSD NVMe ADATA IM2P33F3A, 512 GB |
| sistema | Debian 13, kernel `6.12.86+deb13-amd64` |
| Docker / Compose | 29.4.3 / 5.1.3 |

O teste usou uma instância de PostgreSQL, Kafka, Payment Ingress, Payment Processor e Notification Gateway.

| Recursos durante a fase medida | Execução A | Execução B |
| --- | ---: | ---: |
| CPU média agregada | 2,094 vCPU | 1,158 vCPU |
| maior amostra completa de CPU | 3,399 vCPU | 2,195 vCPU |
| memória média agregada | 1.824,5 MiB | 1.813,4 MiB |
| maior amostra de memória | 1.994,6 MiB | 1.955,8 MiB |

Uma execução de 15 minutos ocupou aproximadamente 2,63 GB no PostgreSQL e 1,99 GB no Kafka, totalizando 4,62 GB.

Dividir esse total pelos cerca de 1,89 milhão de pagamentos planejados para a fase medida produz aproximadamente 2,4 KB acumulados por pagamento. Em uma extrapolação linear, essa ordem de grandeza fica perto de 443 GB por dia. Para o Kafka isoladamente, sete dias do mesmo volume corresponderiam a aproximadamente 1,3 TB.

A extrapolação serve para comparar a ordem de grandeza do armazenamento. O volume observado inclui aquecimento, respostas, repetições e confirmações. Compressão, limpeza de segmentos, retenção e composição da carga também afetam o crescimento. O experimento mostrou que armazenamento e retenção passam a importar antes de CPU se tornar o limite.

Esses números descrevem o ambiente observado e não entram nos três critérios do benchmark.

## Por que as duas execuções importam

A execução A consumiu mais CPU e foi mais lenta, embora código, configuração e carga fossem os mesmos. As operações no PostgreSQL também demoraram mais, mas os dados disponíveis não apontaram uma causa única.

Mostrar apenas a execução B produziria números mais atraentes, mas esconderia uma variação real. Manter as duas mostra que o resultado se repetiu e que até a condição menos favorável permaneceu dentro da meta.

## Onde está a evidência

Os arquivos usados para preservar as duas execuções estão em:

```text
docs/performance/evidence/2026-08-29/
├── profile.json
├── execution-plan.json
├── qualification-run-a-sla-report.json
├── qualification-run-b-sla-report.json
└── checksums.sha256
```

O perfil e o plano registram a carga executada. Os relatórios preservam geração, latência, confirmações e repetições. Os checksums permitem verificar que esses arquivos continuam iguais aos escolhidos como evidência final.

Os relatórios de comparação entre os geradores Go e Rust, presentes no mesmo diretório, pertencem a outro estudo e não fazem parte das execuções finais.

Para executar o mesmo perfil novamente:

```bash
cd load-test
./prepare-performance-environment.sh --profile mixed-outcomes-2k-15m
./run-load-test.sh --profile mixed-outcomes-2k-15m <run-tag>
```

A preparação recria o ambiente local. Uma nova execução não herda a conclusão das anteriores: precisa cumprir novamente todos os critérios.

## Escopo da medição

O benchmark mediu um cenário específico:

* uma instância de cada serviço;
* um broker Kafka com fator de replicação 1;
* gerador e sistema no mesmo host;
* fase principal de 15 minutos;
* corretude end-to-end verificada pelas confirmações e repetições observadas, com as invariantes financeiras cobertas pelos testes transacionais.

Nesse cenário, as duas execuções sustentaram a conclusão do projeto: pelo menos 2.000 pagamentos por segundo, p99 abaixo de 1 segundo e nenhuma confirmação esperada ausente ou contraditória.
