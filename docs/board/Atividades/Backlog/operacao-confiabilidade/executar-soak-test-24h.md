# Executar soak test de 24 horas

- [ ] Validar estabilidade contínua do runtime durante 24 horas

## Objetivo

Exercitar o workload oficial por uma janela ativa de `24 horas` para encontrar degradação progressiva que runs qualificadoras menores não revelam: crescimento de memória ou disco, perda gradual de throughput, aumento de latência, backlog, vazamento de recursos e falhas de recuperação.

## Escopo

* executar somente depois da campanha de uma hora, usando o mesmo runtime e suas medições de crescimento como base de capacidade;
* criar um profile de `24 horas` derivado do workload qualificado, preservando `2.100 TPS` oferecidos, piso rolling de `2.000 TPS`, mix funcional e replays de `5%`;
* usar commit limpo, ambiente novo e host controlado, sem mudanças durante a execução;
* acompanhar por hora throughput rolling, latência, outcomes, replays, CPU, memória, armazenamento, lag e saúde dos processos;
* verificar que nenhuma obrigação causal fica permanentemente pendente e que nenhum componente reinicia ou degrada silenciosamente;
* documentar a evolução temporal e preservar evidência compacta suficiente para verificar o resultado.

## Planejamento de capacidade

O teste produz aproximadamente `181,44 milhões` de pagamentos originais, além dos replays. A extrapolação linear da run atual de `15 minutos` levaria somente os volumes PostgreSQL e Kafka para a ordem de `440 GB` em `24 horas`; esse valor é preliminar e deve ser substituído pela taxa de crescimento medida nas runs de uma hora. O soak não pode começar sem armazenamento dimensionado e monitorado, incluindo margem para resultados, WAL, Kafka e arquivos temporários.

## Critérios de conclusão

* rolling mínimo permanece em pelo menos `2.000 TPS` durante toda a janela ativa;
* p99 end-to-end permanece abaixo de `1 segundo`, globalmente e sem degradação horária material escondida pela agregação;
* não existem outcomes ausentes ou contraditórios nem violações de replay;
* memória, CPU, armazenamento, filas e tempos de processamento não apresentam tendência incompatível com operação contínua;
* nenhum processo reinicia inesperadamente e o drain final conclui as obrigações observáveis;
* qualquer limitação encontrada gera evidência e uma task específica, sem tuning oportunista durante o soak.

## Fora de escopo

* injeção deliberada de falhas ou chaos engineering;
* homologação multi-instância e alta disponibilidade;
* alterar retenção, workload ou recursos durante a execução para fazê-la passar;
* declarar capacidade além de `2.000 TPS` com base neste teste.
