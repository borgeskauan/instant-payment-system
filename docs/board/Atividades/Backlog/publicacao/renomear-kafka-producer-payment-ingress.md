# Renomear `kafka-producer` para `payment-ingress`

- [ ] Alinhar o nome técnico do componente à sua responsabilidade de negócio

## Objetivo

Substituir o identificador `kafka-producer` por `payment-ingress`. O nome atual descreve um mecanismo interno, enquanto o componente é a fronteira HTTP/2 com mTLS que autentica o PSP, recebe mensagens de pagamento e as encaminha ao Kafka.

## Escopo

- Renomear o diretório do projeto, artefato Maven, pacote Java, aplicação, serviço e container Compose.
- Atualizar scripts, testes, certificados, diagnósticos e referências documentais que dependam do identificador técnico.
- Usar **Payment Ingress** como nome legível em documentação, diagramas e logs destinados ao leitor.
- Preservar endpoints, contratos PACS, tópicos Kafka, autenticação mTLS, autorização e comportamento de runtime.

## Validação

- Executar os testes do componente e os testes de integração afetados.
- Construir a stack pelo Compose e executar o smoke funcional oficial.
- Confirmar que não restaram referências técnicas ativas a `kafka-producer`, exceto em histórico deliberadamente preservado.

## Fora de escopo

- Renomear Kafka, tópicos ou outros componentes.
- Alterar responsabilidades, protocolos ou arquitetura do ingresso.
- Requalificar performance longa, salvo se a implementação deixar de ser uma mudança estritamente nominal.
