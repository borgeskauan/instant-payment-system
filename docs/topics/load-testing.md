# Como o teste de carga funciona

Este documento responde a uma pergunta:

> Como saber se o sistema realmente sustentou a carga, em vez de apenas acumular trabalho e recuperar a média depois?

Os resultados, o ambiente e os critérios de aprovação estão em [Performance e evidência](../performance.md). Aqui, o foco é o método usado para produzir e medir a carga.

## O gerador acompanha o pagamento inteiro

O gerador de carga representa as instituições dos dois lados do fluxo:

```text
pagador envia o pagamento
        ↓
recebedor recebe e responde
        ↓
resultado final volta ao pagador
```

Uma resposta HTTP bem-sucedida confirma apenas que a mensagem entrou no sistema. Para o benchmark, o pagamento só termina quando o resultado final compatível volta ao pagador pelo Notification Gateway.

O gerador também executa as repetições previstas e verifica se elas foram aceitas sem produzir um resultado contraditório.

Ele não olha o estado interno das aplicações para concluir que tudo deu certo. O relatório se limita ao trabalho que a própria ferramenta conseguiu criar, acompanhar e observar.

## A mesma configuração produz a mesma carga

Antes de começar, a configuração escolhida é validada e transformada em um plano de execução.

Esse plano determina antecipadamente:

* quais pagamentos devem concluir ou ser rejeitados por saldo insuficiente;
* quais instituições participam de cada pagamento;
* quais pares recebem a maior concentração de tráfego;
* quais mensagens serão repetidas;
* qual resultado deve voltar para cada pagamento.

Essas escolhas vêm da sequência de cada pagamento, não da ordem em que tarefas concorrentes terminam. Executar novamente o mesmo plano preserva a composição da carga.

A configuração original e o plano efetivamente executado são guardados junto com o resultado.

## O sistema não controla a taxa oferecida

O gerador trabalha em **malha aberta** (*open loop*): os instantes dos pagamentos são planejados antes de qualquer resposta existir.

Se o sistema ficar mais lento, o gerador não reduz automaticamente a taxa. Se um pagamento perder seu momento de início, ele também não é empurrado para frente para reparar a média.

```text
momento perdido
      ↓
pagamento não iniciado
      ↓
diferença permanece visível
```

Não existe uma fila de pagamentos atrasados seguida por uma rajada de recuperação.

Essa regra impede que uma execução fique abaixo da meta durante parte do tempo e pareça saudável apenas porque compensou depois.

## Planejar o instante não basta; a requisição precisa começar

O cadenciamento divide o tempo em janelas absolutas de 10 ms. Todas são calculadas a partir do início da fase, portanto o atraso de uma janela não desloca as seguintes.

Preparar um pagamento exatamente em seu instante de início adicionaria ao resultado o tempo necessário para montar a mensagem e conseguir espaço na conexão. Por isso, o gerador prepara o próximo grupo com antecedência.

Preparar não significa iniciar. Um pagamento só conta quando:

1. sua mensagem está pronta;
2. existe capacidade real para abrir uma requisição HTTP/2;
3. seu instante planejado chegou;
4. a requisição começa dentro da janela permitida.

Se alguma dessas condições não for atendida a tempo, o pagamento aparece como não iniciado. Depois que começa corretamente, ele não é descartado por atraso; continua sendo acompanhado até a resposta ou o encerramento do experimento.

Reservar capacidade HTTP/2 é importante porque uma fila interna do cliente poderia aceitar trabalho sem colocá-lo imediatamente na conexão. Sem essa proteção, o relatório registraria como iniciado algo que ainda estava esperando localmente.

As conexões permanecem abertas durante o teste e são aquecidas antes da carga. Isso representa melhor uma instituição cuja infraestrutura mantém um pool de conexões com o sistema.

## O relógio não disputa com a rede ou o relatório

Uma thread dedicada controla apenas os instantes em que os pagamentos começam. Ela não espera respostas HTTP, não processa notificações e não calcula estatísticas.

Rede, respostas do recebedor e repetições são executadas de forma assíncrona. Os eventos observados seguem para um gravador separado, e o relatório é construído somente depois da execução.

Essa divisão mantém o caminho que controla o tempo pequeno e evita que percentis, CSVs ou agregações atrasem a criação da própria carga.

Filas internas possuem limites. Se a ferramenta ficar sem capacidade para acompanhar seu próprio trabalho, a execução termina com erro em vez de reduzir silenciosamente a carga.

## O aquecimento termina quando seu trabalho observável termina

Antes da fase principal, o gerador aumenta a taxa em duas etapas. O objetivo é aquecer conexões, caches e JVMs sem misturar essa inicialização com o período medido.

Quando a geração do aquecimento termina, a ferramenta aguarda tudo que ela própria criou e consegue observar:

* pedidos originais;
* respostas do recebedor;
* resultados finais;
* repetições selecionadas.

A fase principal só começa depois que essas obrigações terminam ou quando o limite de espera é excedido.

Essa condição não afirma que Kafka, PostgreSQL e todas as filas internas estão completamente vazios. Ela garante algo menor e verificável:

> A fase medida não começa enquanto o gerador ainda acompanha trabalho do aquecimento.

## Repetições aumentam a carga, mas não o throughput declarado

Parte dos pedidos e das respostas é enviada novamente dez segundos depois.

As repetições preservam a identidade e o conteúdo da mensagem original. Elas exercitam a idempotência do sistema, mas não ocupam o lugar de pagamentos novos e não contam para o piso de throughput.

O relatório compara quantas foram planejadas, enviadas e aceitas. Uma repetição selecionada que não acontece ou não recebe uma resposta de ingresso bem-sucedida permanece visível como violação.

## Como os resultados são calculados

### Throughput

Um pagamento conta somente se sua requisição original realmente começou dentro da fase medida.

Depois da execução, o relatório avalia todas as janelas contínuas de um segundo contidas nessa fase. A menor contagem encontrada é o **minimum rolling TPS**, ou menor throughput contínuo de um segundo.

Com isso:

* a média não esconde um vale;
* uma rajada posterior não corrige uma janela anterior;
* pagamentos planejados e iniciados permanecem separados.

### Latência

A latência começa quando a requisição original inicia e termina na primeira confirmação final compatível observada pelo pagador.

O tempo usado para preparar a mensagem e a resposta HTTP do ingresso não encerram essa medição.

### Corretude observável

O gerador conhece antecipadamente o resultado esperado de cada cenário. Para cada pagamento iniciado, ele verifica se o pagador recebeu uma confirmação compatível.

Ele registra:

* resultados compatíveis;
* resultados ausentes;
* estados ou motivos incompatíveis;
* respostas causais rejeitadas;
* repetições que não foram executadas ou aceitas.

Confirmações duplicadas e compatíveis são permitidas, pois a entrega é at-least-once. Uma confirmação incompatível continua sendo uma contradição mesmo se outra correta também tiver chegado.

O relatório não relê todos os saldos no PostgreSQL. As invariantes financeiras são verificadas diretamente pelos testes transacionais do sistema.

## Preparar o ambiente e gerar a carga são trabalhos diferentes

A preparação cria um ambiente novo, aguarda os serviços, provisiona os participantes e gera os certificados necessários.

Somente depois disso o comando de execução inicia a carga. Ele exige a mesma configuração usada na preparação e preserva o plano junto com os resultados.

O comando de execução não repete verificações de infraestrutura nem tenta adivinhar que todos os serviços estão ociosos. Essas responsabilidades pertencem à preparação.

Nas qualificações finais, cada execução começou com uma preparação completa e independente.

## Onde a metodologia termina

O desenho assume conscientemente que:

* gerador e sistema compartilham o mesmo host;
* a thread que controla o cadenciamento não usa prioridade de tempo real nem afinidade fixa de CPU;
* 10 ms é a menor unidade temporal contratada;
* a conclusão do aquecimento considera o trabalho do gerador, não todo o estado interno dos serviços;
* a corretude end-to-end observa resultados e repetições, mas não audita novamente todos os saldos;
* diagnósticos ajudam a explicar uma execução, mas não substituem os critérios do relatório.

Dentro desses limites, a propriedade central é simples:

> O throughput inclui apenas pagamentos que realmente começaram em sua janela, e a latência acompanha esses mesmos pagamentos até o resultado final.

## Verificar a implementação

O gerador e o relatório estão em [`load-test/rust-loadtool`](../../load-test/rust-loadtool/). A separação entre preparação e execução está nos scripts [`prepare-performance-environment.sh`](../../load-test/prepare-performance-environment.sh) e [`run-load-test.sh`](../../load-test/run-load-test.sh).
