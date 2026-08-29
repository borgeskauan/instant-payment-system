# Homologar execução multi-instância

## Por que existe

A qualificação atual comprova uma única stack sustentando o workload oficial de
2.000 pagamentos originais por segundo. Uma topologia com duas instalações
concorrentes e PostgreSQL compartilhado possui riscos diferentes de contenção,
isolamento e capacidade agregada e deve ser homologada separadamente quando
essa forma de implantação for priorizada.

Esta task não bloqueia a estabilização da stack única e não pressupõe
Kubernetes. A infraestrutura usada no experimento será escolhida quando a task
for iniciada.

A concorrência interna dos listeners Kafka maior que `1` pertence a esta mesma
frente. Embora use uma única JVM, ela já cria transações financeiras
concorrentes e exercita no PostgreSQL parte dos riscos de locking, idempotência
e participantes quentes que aparecem com múltiplas instâncias.

O baseline atual não é inteiramente sequencial: PACS.008 e PACS.002 possuem
listeners independentes e podem executar simultaneamente. Entretanto, cada
fluxo permanece serializado internamente com `concurrency=1`. Esta task começa
justamente na fronteira ainda não homologada: dois batches concorrentes do
mesmo fluxo e, depois, múltiplas instâncias do SPI.

## Escopo

- [ ] Definir a topologia de duas stacks/instalações independentes que
      compartilham o mesmo PostgreSQL.
- [ ] Definir e homologar a concorrência dos listeners Kafka por fluxo,
      começando pelo PACS.008, antes de escolher a configuração multi-instância.
- [ ] Definir a carga contratada por stack e a capacidade agregada esperada
      antes do experimento.
- [ ] Isolar dados, tópicos, consumer groups, ISPBs, certificados e métricas de
      cada stack.
- [ ] Garantir correlação inequívoca dos pagamentos e outcomes com a stack que
      os originou.
- [ ] Executar os workloads simultaneamente a partir de ambientes limpos e
      preservar os diretórios de resultado de ambas as execuções.
- [ ] Validar correção funcional, idempotência e ausência de efeitos financeiros
      ou notificações duplicados sob concorrência.
- [ ] Medir conexões, locks, waits, query latency, CPU e I/O do PostgreSQL
      compartilhado.
- [ ] Medir throughput rolling, p95/p99 e consumo de CPU/memória de cada stack
      separadamente.
- [ ] Registrar se o PostgreSQL compartilhado impõe uma nova restrição de
      capacidade ou isolamento.

## Critérios de conclusão

- a topologia e o workload concorrente estão documentados e são reproduzíveis;
- os resultados de cada stack podem ser avaliados de forma independente;
- não há regressão de corretude causada pela execução concorrente;
- a capacidade e os limites do PostgreSQL compartilhado estão sustentados por
  evidências;
- qualquer gargalo encontrado gera uma task específica, sem transformar esta
  homologação em uma frente aberta de tuning.

## Fora de escopo

- implementar deploy Kubernetes;
- definir alta disponibilidade ou disaster recovery;
- fazer tuning sem uma hipótese derivada do experimento;
- alterar o workload oficial para facilitar a homologação.
