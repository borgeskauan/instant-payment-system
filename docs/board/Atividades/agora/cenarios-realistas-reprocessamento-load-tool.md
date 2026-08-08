# Cenários realistas e reprocessamento no load-tool

- [ ] Implementar cenários realistas e reprocessamento no load-tool

## Por que existe

O teste atual sustenta alta taxa com um padrão bastante controlado. Para aproximar o experimento de produção, o load-tool precisa fornecer perfis automatizados para cenários diferentes: caminho feliz, saldo insuficiente, rejeições, duplicidade, replay, participantes quentes e variações de carga.

Kafka, retries, restarts e falhas de rede tornam duplicidade e replay inevitáveis. O fluxo precisa provar que reprocessar mensagens não duplica liquidação, que status já liquidado permanece idempotente e que mensagens inválidas não travam o consumo.

## Ordem de trabalho

Esta task implementa primeiro os cenários, a seleção de perfis, os resultados esperados e a coleta consistente das medições.

Os limites finais de latência, throughput, CPU e memória serão definidos e validados em uma segunda passagem, coberta por **Estabilizar teste de carga dentro do budget de CPU** em [`operacao-testes.md`](../../Backlog/operacao-testes.md). Não é necessário fechar esses limites para começar a construir os workloads.

Durante esta primeira passagem, cada perfil pode usar duração curta para validação funcional. Runs longos e gates finais de capacidade ficam para a etapa de estabilização.

## Tarefas

- [ ] Adicionar perfis de teste no load-tool, com múltiplos arquivos de configuração por cenário.
- [ ] Permitir selecionar o perfil no script de execução.
- [ ] Fazer simulação e relatório usarem exatamente o mesmo perfil selecionado.
- [ ] Copiar a configuração efetiva para o diretório de resultados de cada run.
- [ ] Garantir que cada perfil defina carga, distribuição de participantes, valores, fundos provisionados e resultados de negócio esperados.
- [ ] Preservar um perfil uniforme compatível com o cenário atual para servir de comparação.
- [x] Gerar valores de transação variados em vez de valor fixo.
- [x] Simular distribuição desigual entre ISPBs: poucos participantes quentes e muitos participantes frios.
- [ ] Criar cenários com hot ISPB, hot sender, hot receiver e hot partition.
- [ ] Variar taxa de chegada com ramp-up, pico, carga sustentada, queda e período ocioso.
- [ ] Misturar transações aprovadas e rejeitadas no mesmo run.
- [ ] Simular saldo insuficiente real para parte dos pagamentos.
- [ ] Medir rejeições e saldo insuficiente separadamente do caminho que deve receber confirmação final.
- [ ] Validar que a taxa de confirmação considera somente transações cujo resultado esperado exige confirmação.
- [ ] Reprocessar mensagens Kafka já consumidas e validar que não ocorre dupla liquidação.
- [ ] Reemitir status de pagamento já liquidado e validar replay idempotente.
- [ ] Testar duplicidade de `pacs.008` com o mesmo `EndToEndId`.
- [ ] Testar duplicidade de `pacs.002` para pagamento já confirmado.
- [ ] Validar que `notSettledPaymentIds` e atualizações de status continuam corretos com IDs duplicados.
- [ ] Automatizar cenários de reliable PSP delivery: PSP offline, retry após reconexão, restart do `notification-gateway`, ACK perdido e replay sem delivery lógica duplicada.
- [ ] Expor nos resultados contagens de duplicidade, replay e retries.
- [ ] Garantir que retry e replay não alteram saldo nem geram confirmação inconsistente.
- [ ] Comparar o cenário uniforme atual com os cenários realistas para identificar regressões escondidas.

## Critérios de conclusão desta passagem

- os perfis são selecionáveis sem editar o arquivo de configuração principal;
- cada perfil declara quais resultados de negócio produz e como eles serão contabilizados;
- o relatório não classifica rejeição esperada ou falta de liquidez esperada como perda técnica;
- duplicidade e replay possuem invariantes automáticas de saldo, auditoria, outbox e confirmação;
- cada perfil possui pelo menos um run curto registrado que comprova seu funcionamento;
- medições de latência, throughput e uso de recursos são coletadas, mas ainda não precisam satisfazer limites finais;
- qualquer falha funcional encontrada gera correção ou task focada, sem expandir indefinidamente esta task.

## Fora desta passagem

- definir o budget final de CPU e memória por serviço;
- fechar os thresholds definitivos de p95, p99 e throughput;
- tuning de consumers, producers, pools ou banco sem evidência produzida pelos novos cenários;
- executar a validação oficial repetida de 15 minutos;
- validar duas stacks compartilhando o mesmo PostgreSQL;
- implementar a retentativa automática de pagamentos em `ACCEPTED_IN_PROCESS`.
