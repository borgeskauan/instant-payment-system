# Alinhar o harness de load test ao onboarding Linux mínimo

- [ ] Reduzir os requisitos do host a Bash e Docker Compose sem ampliar o escopo do gerador Rust

## Objetivo

Permitir que uma pessoa em uma máquina Linux limpa execute o smoke funcional e as campanhas de performance instalando somente Docker com Compose. A orquestração continua em Shell, o load tool continua responsável apenas por profile, geração e relatório, e diagnósticos continuam externos ao hot path.

## Arquitetura alvo

```text
host Linux
  Bash + Docker Compose
        |
        +-- comando único: prepare, run, smoke e down
        +-- Docker constrói os serviços Java
        +-- Docker constrói e exporta o binário Linux estático do load tool
        +-- container de suporte provisiona fundos e gera certificados
        +-- diagnósticos permanecem adapters externos

Rust load tool
  profile + geração + relatório
```

O ambiente preparado seleciona o profile. O comando `run` deriva essa informação do estado preparado e não recebe o profile novamente.

## Trabalho

### Fatia 1 — Lean Linux harness

- Remover Python do caminho operacional fazendo o Rust emitir os registros tipados de preparação que Shell consome.
- Substituir os entrypoints públicos separados por um único comando pequeno com subcomandos `prepare`, `run`, `smoke` e `down`.
- Fazer `run` consumir o único ambiente preparado vigente sem repetir `--profile`.
- Manter Shell restrito a coordenação de processos e arquivos; não interpretar JSON nem duplicar regras do profile.

### Fatia 2 — Docker-only onboarding

- Construir o load tool em imagem versionada e exportar um binário Linux estático para execução direta no host, preservando cache de build e comportamento de performance.
- Executar geração de certificados PSP e provisionamento por uma imagem de suporte com as ferramentas necessárias, removendo Cargo, `curl` e OpenSSL dos requisitos do host.
- Usar healthchecks do Compose e `docker compose up --wait` para readiness das aplicações onde isso representar uma propriedade real; manter separado somente o check específico de estabilidade dos consumer groups Kafka.
- Preservar JFR, métricas PostgreSQL, recursos de containers e logs como adapters externos e desativáveis, sem mover diagnóstico para o Rust.

## Critérios de conclusão

- Uma máquina Linux com Bash e Docker Compose consegue executar `smoke` a partir de um clone limpo.
- Java, Maven, Rust/Cargo, Python, Node, `curl` e OpenSSL não são dependências do host para executar o core e o load test.
- O comando público mostra a sequência simples de preparação e execução e falha com mensagens claras quando Docker ou o ambiente preparado não estão disponíveis.
- O gerador Rust não conhece Docker, stack, provisionamento, certificados ou diagnósticos.
- Profiles, workload, pacing, resultados e evidências permanecem semanticamente equivalentes ao harness anterior.
- Testes automatizados cobrem preparação, ambiente divergente/incompleto, execução, falhas dos adapters e shutdown dos diagnósticos.
- Um smoke limpo e uma comparação controlada confirmam equivalência funcional; qualquer mudança material no caminho medido exige validação de performance proporcional ao risco.

## Fora de escopo

- Suporte nativo a Windows; no Windows, o caminho aceitável continua sendo WSL2.
- Reescrever toda a orquestração em Python ou Rust.
- Colocar infraestrutura ou diagnósticos dentro do load tool.
- Alterar workloads, SLAs, recursos da stack ou comportamento dos componentes do core.
- Transformar o Compose local em solução de deployment ou alta disponibilidade.
