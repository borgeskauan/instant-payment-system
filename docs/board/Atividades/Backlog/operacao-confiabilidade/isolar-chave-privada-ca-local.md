# Isolar a chave privada da CA local

## Por que existe

O ambiente local monta todo o diretório `infra/certs/local/ca/` no Payment Ingress, no Notification Gateway e nos PSPs da demo. Embora essas aplicações usem apenas `ca.crt`, a montagem também torna `ca.key` visível dentro dos containers.

A chave privada deve permanecer disponível somente para o processo local que emite certificados.

## Escopo

- [ ] Fazer os containers de aplicação receberem apenas o certificado público `ca.crt`.
- [ ] Manter `ca.key` acessível ao serviço `certs-init`, que precisa assinar os certificados locais.
- [ ] Preservar a inicialização a partir de um clone limpo, considerando que `ca.crt` ainda não existe antes da primeira execução do `certs-init`.
- [ ] Aplicar a correção ao Compose do core e ao Compose da reference demo.
- [ ] Adicionar uma verificação de contrato que impeça a reintrodução da montagem da chave privada em containers de aplicação.
- [ ] Atualizar `infra/certs/README.md` para refletir o isolamento efetivamente implementado.

## Critérios de aceite

- nenhum container de aplicação consegue acessar `ca.key`;
- o fluxo local de geração e rotação de certificados continua funcionando;
- core, load test e reference demo continuam iniciando em ambiente limpo;
- somente o componente responsável pela emissão local recebe a chave privada da CA.

## Fora de escopo

- implementar uma PKI de produção;
- adicionar CSR, revogação, inventário ou rotação automática;
- alterar o contrato de identidade mTLS ou autorização por ISPB.
