# Consolidar evidências finais de performance

* [ ] Preservar e documentar a evidência final depois que a meta de performance for atingida e confirmada

## Estado atual

* [x] selecionar o run qualificador final e sua repetição limpa;
* [x] consolidar método, decisões, resultados e limitações no
  [relatório final](../../../../performance/2k-tps-stabilization.md);
* [x] preservar profile, plano normalizado, relatórios e checksums no
  [manifesto de evidências](../../../../performance/evidence/2026-08-27/manifest.md);
* [ ] arquivar os dois bundles brutos fora do Git comum, com checksum do bundle
  completo, localização e instruções de recuperação;
* [ ] validar a recuperação do arquivo externo antes de remover os bundles
  locais e resultados intermediários.

A task permanece aberta somente pelas etapas de preservação externa. Os
artefatos locais não devem ser apagados até elas terminarem.

## Por que existe

Durante a estabilização, os bundles completos permanecem locais e a task ativa
registra as decisões tomadas. Versionar todos os experimentos intermediários
adicionaria muito volume ao repositório sem melhorar o trabalho de tuning.

Depois que o sistema sustentar o workload oficial dentro dos SLAs e do budget de
recursos, precisamos transformar o resultado final em evidência durável,
auditável e reproduzível.

## Quando iniciar

Somente depois que a task de estabilização:

* atingir a meta de 2.000 pagamentos originais por segundo durante os 15 minutos
  ativos;
* preservar os outcomes funcionais e os SLAs definidos;
* respeitar o budget de recursos;
* confirmar o resultado com uma repetição limpa e comparável.

## Trabalho

* selecionar o run qualificador final e sua repetição de confirmação, sem
  promover todos os experimentos intermediários;
* registrar revisão Git, estado do worktree, imagens dos serviços, configuração
  efetiva e limites de CPU e memória;
* preservar no repositório os artefatos compactos necessários, incluindo perfil,
  plano de execução, relatórios e um manifesto da evidência;
* arquivar os bundles brutos completos fora do Git comum, com checksum, formato,
  localização e instruções de recuperação documentados;
* excluir do arquivo credenciais, certificados efêmeros e outros dados que não
  devam ser preservados;
* documentar o comando de reprodução, ambiente, resultados, limitações e relação
  entre a evidência compacta e o bundle bruto;
* revisar a documentação do projeto para distinguir o resultado final dos
  diagnósticos históricos e remover referências que ficaram obsoletas;
* remover bundles locais intermediários somente depois de validar o arquivo
  final e sua recuperação.

## Critérios de conclusão

* a afirmação final de capacidade pode ser auditada a partir do repositório e do
  arquivo bruto referenciado;
* os checksums dos bundles preservados são válidos;
* a documentação permite reproduzir o workload e interpretar o resultado;
* os artefatos finais identificam inequivocamente código, configuração, imagens e
  recursos usados;
* CSVs, JFRs, logs e demais artefatos volumosos não são adicionados diretamente
  ao Git comum;
* a limpeza dos resultados intermediários não remove nenhuma evidência ainda
  necessária.

## Fora de escopo

* preservar todos os runs executados durante a estabilização;
* reprocessar ou regenerar relatórios históricos;
* criar uma nova camada de visualização;
* fazer novos ajustes de performance, alterar SLAs ou redefinir o workload.
