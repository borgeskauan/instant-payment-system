# Separar a reference demo do core

- [ ] Isolar DICT, PSP simulado e Angular como uma reference demo sem garantias do core

## Decisão

`dict`, `payment-service-provider` e `payment-app` continuam no repositório porque permitem visualizar interativamente o produto. Eles não fazem parte do sistema cuja corretude, capacidade, durabilidade e disponibilidade são homologadas.

A relação arquitetural é unidirecional:

```text
reference demo ──────► core

core ──────────X─────► reference demo
```

O core não será movido para um diretório `core/`, pois isso alteraria caminhos de build, Compose, scripts, certificados e documentação sem melhorar a separação. Somente a demo será agrupada fisicamente.

## Layout alvo

```text
instant-payment-system/
├── spi/
├── kafka-producer/
├── notification-gateway/
├── load-test/
├── infra/
├── scripts/
├── docs/
└── demo/
    ├── dict/
    ├── payment-service-provider/
    └── payment-app/
```

## Níveis de compromisso

| área | compromisso |
| --- | --- |
| core | código testado e benchmarkado; correctness, performance e documentação canônica |
| `demo/` | happy path reproduzível para demonstração, sem garantias operacionais ou de performance |
| histórico Git | experimentos e implementações abandonadas |

O README deve descrever a demo como **“Reference demo — not part of the benchmarked core.”**

## Objetivo

Preservar uma demonstração visual do fluxo Pix sem fazer o núcleo parecer dependente de sete serviços igualmente importantes e sem transformar a demo em um segundo produto mantido.

O happy path demonstrado é:

```text
subir core
→ subir reference demo
→ abrir PSP A
→ identificar o destinatário no DICT
→ enviar Pix
→ SPI processar
→ PSP B receber
→ frontend apresentar o resultado
```

## Escopo

1. Inventariar referências ativas aos três projetos em Compose, scripts, certificados, coleções, documentação e tasks.
2. Mover os projetos para `demo/` preservando seus nomes internos e o histórico Git.
3. Remover o DICT e qualquer dependência da demo do Compose principal.
4. Criar um Compose próprio da demo, conectado apenas às interfaces públicas do core.
5. Criar um README próprio com propósito, comandos, limitações e screenshots do fluxo.
6. Corrigir somente bugs que impeçam o happy path, incluindo criação implícita de contas ou reset incoerente quando comprovadamente necessários.
7. Criar um smoke básico e reproduzível do fluxo demonstrativo.
8. Atualizar tasks e documentação que ainda tratem DICT, PSP ou frontend como parte do core.

## Regras de dependência

* código, build, testes, configuração e startup do core não podem depender de `demo/`;
* o Compose principal deve subir e validar o core sem conhecer a demo;
* a demo pode depender das APIs públicas, certificados e rede publicados pelo core;
* falha ou ausência da demo não pode invalidar testes, benchmarks ou CI do core;
* performance, durabilidade, disponibilidade e confiabilidade da demo não integram as garantias do projeto;
* o load-tool continua sendo o instrumento autoritativo para validar engenharia e não será substituído pelo PSP simulado.

## Fora de escopo

* persistência produtiva no PSP;
* cobertura abrangente de testes da demo;
* refatoração arquitetural ou aplicação de hexagonal por uniformidade;
* reescrita da modelagem PACS;
* tratamento completo de todos os erros;
* performance, HA ou observabilidade sofisticada da demo;
* equivalência com um PSP real;
* expansão funcional do DICT;
* implementar `previewId` ou modernizações que não sejam necessárias ao happy path;
* mover fisicamente os componentes do core para outro diretório.

## Critérios de conclusão

* os três projetos estão agrupados sob `demo/` e o histórico permanece reconhecível;
* nenhuma dependência `core → demo` permanece no código, build, configuração ou startup;
* o Compose principal continua reproduzindo o core sem a demo;
* o Compose e o README da demo permitem executar o happy path separadamente;
* um smoke básico prova consulta DICT, envio, processamento e recebimento do resultado;
* bugs corrigidos estão estritamente ligados à reprodução desse caminho;
* README e documentação distinguem explicitamente guarantees do core e limitações da demo;
* tasks dependentes são encerradas, reclassificadas como trabalho da demo ou atualizadas;
* nenhum cleanup ou modernização ampla do PSP, DICT ou Angular é incorporado à migração.
