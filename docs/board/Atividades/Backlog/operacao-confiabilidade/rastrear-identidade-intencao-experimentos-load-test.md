# Rastrear identidade e intenção dos experimentos de carga

## Objetivo

Tornar cada bundle de load test suficiente para identificar automaticamente o código executado e preservar a intenção mínima informada pelo executor, sem criar um sistema de gestão de experimentos.

## Contrato mínimo

O executor fornece somente:

```json
{
  "hypothesis": "Reducing the fetch minimum reduces PACS.008 batch tail",
  "controlRun": "pacs008-target-formation-wallclock/20260823_141319",
  "change": "pacs008.fetchMinBytes: 131072 -> 57344"
}
```

`controlRun` é opcional para baseline e diagnóstico sem controle.

O runner coleta automaticamente:

* `runId` e timestamp;
* commit Git;
* indicador de working tree suja;
* patch e SHA-256 quando houver mudanças não commitadas;
* SHA-256 do profile e do plano de execução.

Digests de binários e imagens permanecem no manifesto geral do bundle quando disponíveis; não entram no contrato preenchido pelo executor.

## Regras

* Persistir intenção e identidade dentro do bundle antes da execução medida.
* Não inferir hipótese ou mudança pelo nome da tag.
* Não duplicar workload, resultados ou métricas: profile, execution plan e relatório continuam autoritativos.
* Não criar `type`, `expectedEffect`, modelo genérico de variáveis ou arquivo de avaliação.
* Conclusão, confiança e limitações permanecem na documentação da investigação.
* Não reconstruir retroativamente metadata ausente dos runs históricos.

## Validação

* Run limpo registra commit e `dirty=false` sem patch.
* Run com mudança tracked registra `dirty=true`, patch byte a byte recuperável e checksum válido.
* Mudanças staged e unstaged são preservadas.
* Arquivo de intenção inválido falha antes de gerar carga.
* Baseline funciona sem `controlRun`; candidato preserva a referência informada.
* Bundle continua interpretável somente com seus artefatos, sem consultar o nome da branch ou a working tree atual.

## Fora de escopo

* Dashboard ou banco de experimentos.
* Indexação automática do histórico.
* Upload externo.
* Política obrigatória de working tree limpa.
* Avaliação automática de manter ou descartar uma mudança.
