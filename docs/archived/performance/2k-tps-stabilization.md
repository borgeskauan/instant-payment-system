# Como a stack foi estabilizada em 2.000 pagamentos por segundo

## Função deste documento

Este é o resumo histórico da campanha de estabilização. A afirmação vigente, o ambiente, a workload e os artefatos promovidos estão em [performance e evidência](../../performance.md). Os resultados intermediários que mudaram uma decisão permanecem no [apêndice experimental](experimental-findings.md).

O objetivo aqui é mostrar como o limite migrou e por que o desenho final não pode ser atribuído a uma única otimização.

## Ponto de chegada

Duas execuções consecutivas no commit `1351ea5` qualificaram independentemente:

| Sinal | Execução A | Execução B |
| --- | ---: | ---: |
| originais planejados / executados | 1.890.000 / 1.889.369 | 1.890.000 / 1.890.000 |
| mínimo rolling de 1 segundo | 2.017 TPS | 2.079 TPS |
| p99 end-to-end | 855.202 ms | 265.195 ms |
| outcomes ausentes ou contraditórios | 0 | 0 |
| falhas na execução das repetições | 0 | 0 |

O profile oferecia 2.100 pagamentos originais por segundo durante 15 minutos e exigia piso rolling de 2.000 TPS. Média e quantidade total não compensavam uma janela abaixo do requisito. Replays de `pacs.008` e `pacs.002` adicionavam 5% de carga cada, sem substituir originais.

Os relatórios, profile, plano normalizado e checksums estão no [manifesto final](../../performance/evidence/2026-08-29/manifest.md).

## Método

A campanha seguiu quatro regras:

1. preservar workload e recursos dentro de cada A/B;
2. mudar uma variável relevante por vez;
3. medir trabalho útil e custo por pagamento, não somente CPU ocupada;
4. promover um ganho apenas depois de observar novamente o caminho end-to-end.

Runs qualificadores sempre recriaram containers e volumes. JFR, `pg_stat_statements`, planos SQL, WAL, locks, I/O e amostras de containers foram usados para localizar custo; instrumentação intrusiva foi tratada como diagnóstico, nunca como prova de capacidade.

## A workload também precisou ser corrigida

O baseline original conseguia terminar com média próxima de 2.000 TPS enquanto carregava atraso e o recuperava em rajadas. Um run chegou a média de `1.999,999 TPS`, mínimo rolling zero e pico de `7.105 TPS`.

O contrato passou a usar buckets absolutos, sem carry-over, e a medir toda janela contínua de um segundo dentro do active. Mais tarde, admissão passou a exigir capacidade HTTP/2 reservada antes do commit semântico do pagamento; observar `ready()` sem adquirir um stream ainda permitia backlog invisível dentro do cliente.

O warmup também deixou de ser um `sleep` genérico. A stack fria mostrou compilação relevante entrando no active e obrigações remanescentes entre fases. O perfil final usa bootstrap de 500 TPS, estágio estável de 1.500 TPS e gate limitado às obrigações observáveis do próprio gerador.

Sem essas mudanças, uma melhora aparente do sistema poderia ser apenas uma alteração na workload realmente oferecida.

## Como o gargalo migrou

| Estágio | Sinal dominante | Decisão |
| --- | --- | --- |
| baseline de ingresso | milhares de handshakes TLS e ingresso próximo de um core | conexões persistentes, prewarm e HTTP/2 obrigatório |
| ingresso liberado | PostgreSQL próximo de um core | reduzir transações, WAL e lifecycle de persistência |
| reliable push | delivery, claim e ACK dominavam CPU SQL | batching e depois remoção do lifecycle por Pull |
| híbrido banco + memória + Kafka | reconciler crescia com o histórico mesmo sem falhas | Kafka como log durável; PostgreSQL mantém somente a outbox transacional |
| gerador Go | média correta, mas stalls raros quebravam o rolling | gerador Rust com pacing, planning e admissão de ownership explícito |
| estado final | folga distribuída a 2k; fila no consumer `pacs.008` a 4k | encerrar a qualificação no alvo comprovado e deixar escala adicional fora do escopo |

Essa sequência importa porque o primeiro gargalo escondia o seguinte. Antes do pool de conexões, o ingresso consumia o host abrindo TLS e impedia que a carga alcançasse o core. Depois, o PostgreSQL ficou visivelmente dominante. Após remover delivery state e writes desnecessários, a regularidade do próprio gerador tornou-se o limite para provar o piso sustentado.

## Decisões que permaneceram

### Modelo financeiro

Buckets de saldo foram substituídos por uma disponibilidade por participante e reserva implícita em `WAITING_ACCEPTANCE`. Isso removeu liquidez artificial e reduziu a transição final a creditar o recebedor ou devolver a reserva ao pagador.

### Persistência em lote

Consumers do SPI e Gateway passaram a processar polls inteiros, e operações SQL relevantes usaram arrays/`unnest` ou updates agregados. Um consumer por fluxo produziu lotes maiores no PostgreSQL de um core sem acrescentar concorrência que apenas disputaria a mesma CPU.

Os limites Kafka foram escolhidos pela distribuição real, não pelo valor configurado. Por exemplo, elevar `max.poll.records` de `220` para `500` no `pacs.002` aumentou o lote médio de `129,084` para `162,806`, não para 500, e reduziu callbacks em `20,37%`.

### Layout físico

Tipos compactos reduziram tempo e WAL por row. Fillfactor 50 favoreceu updates HOT, embora aumentasse espaço e custo de insert. Índices de auditoria sem consumidor foram removidos. Essas mudanças foram mantidas por mecanismos físicos reproduzíveis, não por uma única cauda end-to-end favorável.

### Entrega de notificações

O reliable push com ACK persistido foi substituído por Pull com cursor. Em seguida, o híbrido com `delivery_index` e reconciler cedeu lugar a Kafka como log operacional de sete dias. Essa foi a maior remoção estrutural de trabalho no PostgreSQL.

### Gerador

O load tool Rust separa geração medida de relatório, prepara requests antes da fronteira e reserva capacidade HTTP/2 antes de admitir trabalho. O ganho veio do desenho de ownership; canais maiores, pinning e spin prolongado não resolveram os misses restantes.

## Ganhos locais que não viraram capacidade

Dois resultados impediram que microbenchmark fosse tratado como conclusão:

- simplificar o update de `pacs.002` reduziu seu tempo SQL em `69,69%`, mas o A/B end-to-end concluiu menos outcomes porque o limite migrou para outras escritas;
- um preselect antes do insert de `pacs.008` reduziu o SQL isolado em mais de `65%`, mas acrescentou leitura ao hot path, elevou o SQL global em `5,61%` e piorou o p99 em `26,51%`.

O primeiro permaneceu como simplificação local; o segundo foi descartado. A regra durável foi repetir o fluxo causal completo depois de toda otimização relevante.

## Recursos e variância

Nos runs finais, memória média da stack ficou próxima de 1,8 GiB. CPU média agregada variou de `2,094 vCPU` na execução A para `1,158 vCPU` na B, apesar de código, configuração e workload equivalentes.

PostgreSQL, ingresso e Gateway mostraram tempos maiores na A, mas a telemetria não isolou uma causa única entre frequência de CPU, scheduling, atividade externa e trabalho simultaneamente pendente. Preservar a execução A evita selecionar apenas a amostra favorável: ela foi a condição observada com menor margem e ainda qualificou.

## Limite exploratório

Diagnósticos a 4.000 TPS iniciaram quase toda a carga e preservaram outcomes, mas o mínimo rolling ficou entre 3.920 e 3.960 TPS e o p99 entre 1,36 e 2,45 segundos. A fila migrou para o processamento de `pacs.008`.

Esse resultado localiza a próxima fronteira; não qualifica 4.000 TPS nem autoriza inferir comportamento multi-instância.

## Limitações

- A maior parte dos experimentos foi sequencial, não contrabalançada.
- Core e gerador evoluíram durante a campanha; comparações distantes demonstram migração, não efeito isolado.
- Gerador e stack compartilharam o host.
- O ambiente usou uma instância por serviço e um broker Kafka.
- Amostras de CPU, locks e I/O não provam ausência de picos entre observações.
- Os 15 minutos qualificados não demonstram estabilidade por uma hora ou 24 horas.

O conhecimento durável da campanha é o método: medir a workload real, remover o primeiro custo acidental dominante, observar onde o limite migrou e repetir o sistema completo antes de promover a mudança.
