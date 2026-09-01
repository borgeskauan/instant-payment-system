# Reconciliar a outbox de notificações em runtime

## Por que existe

O SPI publica notificações pelo fast path após o commit e retenta em memória quando o Kafka falha. As linhas que já estavam na outbox também são recuperadas durante o startup.

Existe, porém, uma lacuna de liveness: se uma obrigação commitada não alcançar a fila em memória ou se o worker parar inesperadamente, ela permanece durável no PostgreSQL, mas só volta a ser descoberta depois de um restart.

## Objetivo

Recuperar essas linhas sem reiniciar o SPI, preservando o fast path atual e sem consultar a outbox a cada nova notificação.

O desenho alvo deve continuar usando um único worker:

```text
AFTER_COMMIT → fila em memória → publicação imediata

timer interno do mesmo worker
→ consulta periódica de linhas antigas da outbox
→ mesma rotina de publicação e remoção
```

## Escopo

- [ ] Adicionar ao worker um timer de reconciliação independente da chegada de novos lotes.
- [ ] Consultar apenas linhas anteriores a um período de segurança, evitando disputar notificações recém-commitadas com o fast path.
- [ ] Processar fast path e reconciliação no mesmo worker, sem criar um segundo publisher concorrente.
- [ ] Reutilizar a mesma lógica de publicação, retry e remoção para os dois caminhos.
- [ ] Tornar segura a corrida em que uma linha já foi publicada ou removida pelo outro caminho, sem produzir retry infinito.
- [ ] Criar ou ajustar o índice de `created_at` somente se o plano da consulta demonstrar necessidade.
- [ ] Medir em um diagnóstico curto o custo da varredura periódica sobre o PostgreSQL.
- [ ] Documentar o intervalo de reconciliação e a latência adicional aceita nesse failure path.

## Critérios de aceite

- uma obrigação que perdeu o fast path é publicada sem restart do SPI;
- o caminho normal não executa um `SELECT` da outbox para cada evento;
- indisponibilidade transitória do Kafka continua retendo e retentando o lote corrente;
- fast path e reconciliação não criam livelock nem retry infinito ao encontrarem a mesma obrigação;
- duplicatas continuam permitidas apenas conforme a semântica at-least-once;
- testes cobrem recovery em runtime, corrida com o fast path, falha de publicação e shutdown;
- o custo periódico no PostgreSQL permanece pequeno diante do workload qualificado.

## Fora de escopo

- adicionar status, lease ou lifecycle de publicação à outbox;
- criar uma segunda worklist persistente;
- introduzir múltiplos workers de publicação;
- alterar o protocolo pull ou a retenção do Kafka;
- prometer recuperação além da janela operacional já definida para as notificações.
