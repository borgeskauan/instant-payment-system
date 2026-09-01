### Links para leitura
- [ ] https://www.reddit.com/r/brdev/comments/1m52m5a/como_o_pix_deve_funcionar_por_dentro/

- [x] https://cwi.com.br/blog/como-funcionam-as-apis-do-pix/
- [x] https://www.bcb.gov.br/estabilidadefinanceira/pix
- [ ] https://www.bcb.gov.br/estabilidadefinanceira/sfn
- [ ] https://www.bcb.gov.br/estabilidadefinanceira/spb
- [ ] https://www.bcb.gov.br/estabilidadefinanceira/indicespixmetodologias
- [ ] https://www.redhat.com/pt-br/resources/central-bank-of-brazil-case-study
- [ ] https://www.bcb.gov.br/content/estabilidadefinanceira/cedsfn/Manual_de_Seguranca_PIX.pdf

**API do PIX:**
- [ ] https://github.com/bacen/pix-api
- [ ] https://www.bcb.gov.br/content/estabilidadefinanceira/pix/API-DICT.html#tag/Key
- [ ] https://www.bcb.gov.br/content/estabilidadefinanceira/pix/Regulamento_Pix/II_ManualdePadroesparaIniciacaodoPix.pdf

**DICT (Banco de dados de informações cadastrais):**
- [ ] https://www.bcb.gov.br/content/estabilidadefinanceira/pix/API-DICT.html
- [ ] https://www.bcb.gov.br/estabilidadefinanceira/dict
- [ ] https://www.bcb.gov.br/content/estabilidadefinanceira/pix/API-DICT.html#tag/Directory

**Funcionamento do sistema financeiro:**
- [ ] https://www.investopedia.com/terms/d/double-entry.asp

### Podcasts
https://open.spotify.com/episode/0r7a7HORZspD35Dn7y4WTY (BCB)
15:58: Fluxo de transferência do PIX
- Participante coleta informações sobre a agência e conta, além de nome e instituição financeira usando a API do DICT
- Solicitação da transação é enviada para o BCB
- BCB envia um pedido de aceite para a instituição recebedora
- Se a instituição recebedora aceitar, o BCB realiza a transação e envia para as duas instituições um aviso de que a transação foi concluída com sucesso
	- A transação é realizada usando a conta PI que cada instituição tem com o BCB
	- O aviso da transação vai para as instituições para que elas ajustem os saldos nas contas dos clientes
	- Isso evita transações fraudulentas e mantem o BCB sobre controle do sistema financeiro, evita créditos sem débitos.
Não existe saldo individualizado nas contas, em qualquer banco. Todos os bancos possuem uma conta no banco central. O saldo de todos os clientes corresponde ao montante que a instituição possui nessa conta no banco central. Internamente, o banco divide esse saldo entre as contas de seus clientes.

Documentação pertinente: https://www.bcb.gov.br/content/estabilidadefinanceira/pix/Regulamento_Pix/III_ManualdeFluxosdoProcessodeEfetivacaodoPix.pdf

23:10: Comunicação entre participantes e o BCB é por REST
Para mensageria utilizam Kafka
**Módulos (liquidação)**
- Um responsável por receber a mensagem e colocar no Kafka
- Outro responsável por pegar do Kafka e validar a assinatura, transformar em outro objeto e mandar para o Kafka de novo (outro tópico)
- Outro módulo por fazer a liquidação
- Outro módulo por entregar aos participantes as mensagens

24:42: DICT
**Módulos (DICT)**
DICT é o banco de dados do BCB. Armazena dados sobre as contas bancárias dos clientes
Participantes realizam consultas por HTTP/REST
Usa banco de dados SQL, usa comunicação síncrona mesmo, sem mensageria

25:48: Infraestrutura
Usam on-premises, Kubernetes

27:40: Testes de carga
Criaram um gerador de carga para realizar os testes.
Realizam os testes com as partes individuais, mas também com todo o sistema em conjunto
Requisito inicial era processar 2000 operações por segundo com p99 de 4.6s, mas o pico foi de 400 op/s.

https://open.spotify.com/episode/3CZvidvXDXMXGWvYGuZig3 (Banco do Brasil)
22:59: Tempo de resposta da instituição financeira:
- Ao receber um PIX, temos 2.4s para responder se aceita ou não
- Para entregar a solicitação de tranferência, tem-se 1.5s, a partir do momento da solicitação do cliente

26:43: Mínimo para ser homologado é de 4000 pagamentos por minuto. Pico do Banco do Brasil é de 11000 por minuto.

28:54: Tem um pooling feito após a instituição fazer o pedido de transferência, para verificar se existem novas mensagens para aquele pedido.

**Anotações sobre o funcionamento do sistema financeiro e de transações:**
A cada transação como PIX/TED/DOC ou depósito a solicitação é enviada primeiro para o banco central para eles aprovarem e depois o banco realiza os demais procedimentos.

Geralmente um ledger financeiro é um log imutável: você nunca apaga nada, só adiciona transações numa lista. O cálculo do saldo é feito somando todos os valores de todas as transações, negativas e positivas.

Toda transação tem que sair de uma conta e ir pra outra, então não tem como "inventar" os valores que nem você falou. Pelo menos não sem a cooperação de alguém do outro lado de onde o dinheiro tá saindo, que por sua vez vai ter que tirar de algum lugar, e por aí vai. 

Exemplos de banco de dados imutável:
https://www.datomic.com/
