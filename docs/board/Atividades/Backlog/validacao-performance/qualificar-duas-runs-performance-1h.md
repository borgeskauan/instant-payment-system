# Qualificar duas runs de performance de 1 hora

- [ ] Executar duas runs consecutivas de uma hora no runtime final

## Objetivo

Verificar se a capacidade já demonstrada por `15 minutos` permanece válida durante uma janela ativa de `1 hora` e se o resultado é imediatamente repetível sobre dois ambientes novos.

## Escopo

* criar um profile de uma hora derivado de `mixed-outcomes-2k-15m`, alterando somente a duração ativa;
* preservar `2.100 TPS` oferecidos, piso rolling de `2.000 TPS`, mix `80/20`, tráfego quente e replays PACS.008/PACS.002 de `5%`;
* executar toda a campanha sobre um único commit limpo;
* executar `prepare → run A → validar → prepare → run B → validar`, recriando containers e volumes antes de cada run;
* não fazer tuning, alterar configuração ou selecionar amostras entre A e B;
* comparar throughput, latência, corretude, misses do gerador, recursos e crescimento de disco ao longo de cada hora;
* preservar como evidência compacta o commit, profile, execution plan, relatórios A/B, manifesto e checksums.

## Critérios de conclusão

* as duas runs sustentam rolling mínimo de pelo menos `2.000 TPS` durante toda a janela ativa;
* p99 end-to-end permanece abaixo do threshold interno de `1 segundo` em cada run;
* não existem outcomes ausentes ou contraditórios nem violações de replay;
* não existem falhas técnicas, reinícios inesperados ou backlog causal não concluído;
* a evolução temporal de latência, CPU, memória e disco está documentada, sem representar média global como estabilidade;
* a campanha inteira é reiniciada depois de investigação caso uma das runs não qualifique.

## Pré-condição operacional

Uma run de `15 minutos` deixou aproximadamente `4,62 GB` nos volumes PostgreSQL e Kafka. Antes da campanha, medir novamente o crescimento e reservar pelo menos `25 GB` no filesystem do Docker para cada ambiente de uma hora; como o preparador remove os volumes anteriores, esse espaço é de pico, não a soma das duas runs. Reservar também espaço separado para os dois diretórios de resultado.

## Fora de escopo

* alterar o contrato de capacidade;
* tuning durante a campanha;
* homologação multi-instância ou alta disponibilidade;
* usar esta campanha como substituto do soak test de `24 horas`.
