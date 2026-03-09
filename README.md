# 🎟️ TicketFlow Simulator

![Java](https://img.shields.io/badge/Java-21-orange?style=for-the-badge&logo=java)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.2+-brightgreen?style=for-the-badge&logo=spring)
![Redis](https://img.shields.io/badge/Redis-red?style=for-the-badge&logo=redis)
![RabbitMQ](https://img.shields.io/badge/RabbitMQ-FF6600?style=for-the-badge&logo=rabbitmq)
![Docker](https://img.shields.io/badge/Docker-blue?style=for-the-badge&logo=docker)

Um laboratório prático de **Sistemas Distribuídos** e **Engenharia de Performance** criado para resolver um dos problemas mais clássicos da computação: **Race Conditions (Condições de Corrida)** em sistemas de alta concorrência (venda de ingressos).

## O Desafio: O Teste de Estresse (I/O Bound)
Como garantir que 1.000 pessoas tentando comprar 10 ingressos no exato mesmo milissegundo não resultem em "ingressos fantasmas" (vender mais do que o estoque)? Em sistemas mal desenhados, as múltiplas threads leem o banco de dados ao mesmo tempo, burlando a validação de estoque.

Este projeto propõe uma arquitetura blindada que processa milhares de requisições simultâneas em menos de 1 segundo, mantendo **100% de consistência** matemática, sem travar o processador.

## Arquitetura e Solução

Para suportar o pico extremo de acessos, a arquitetura foi desenhada em camadas:

1. **Virtual Threads (Java 21):** Substituem as pesadas *Platform Threads*. Permitem que a aplicação inicie milhares de conexões simultâneas (I/O) sem esgotar a memória ou a CPU do servidor.
2. **Redis (O "Escudo Atômico"):** Atua como o validador principal de estoque. Usando operações atômicas (`decrement`), ele atende as threads na memória RAM com consistência absoluta. Se o estoque é 10, o 11º usuário é sumariamente barrado em milissegundos.
3. **RabbitMQ (Mensageria):** As requisições aprovadas pelo Redis não vão direto para o Banco de Dados (que é lento). Elas são enviadas para uma fila assíncrona, amortecendo o impacto (*Load Leveling*).
4. **Consumer & Database:** Um worker consome a fila do RabbitMQ de forma cadenciada e salva no banco de dados com segurança.

### Fluxo da Informação
`Usuário -> API Spring Boot -> (Validação Atômica) Redis -> RabbitMQ -> Consumer -> Banco de Dados`

##  Como Executar o Laboratório

A infraestrutura foi totalmente orquestrada para subir com um único comando, eliminando o problema de "na minha máquina funciona".

### Pré-requisitos
* Docker e Docker Compose instalados.

### Passos
1. Clone o repositório:
   ```bash
   git clone [https://github.com/jmello01/ticketflow-simulator.git](https://github.com/jmello01/ticketflow-simulator.git)
   cd ticketflow-simulator

2. Suba a infraestrutura completa (App, Redis, RabbitMQ):
   ```bash
   docker-compose up --build -d

3. Acesse o Dashboard de Observabilidade no seu navegador:
     
       http://localhost:8080

## Realizando o Teste de Estresse
O sistema possui endpoints específicos para controle de estado e simulação.

1. Limpe a Bancada (Obrigatório antes de cada teste):
Acesse a URL para resetar o Redis e a memória interna (evitando estado acumulado/lixo de memória):
http://localhost:8080/api/simulation/reset?eventId=1

2. Dispare a Carga:
Vá no Dashboard (localhost:8080), defina 1000 threads e 10 ingressos, e clique em disparar.

3. Resultado Esperado: O sistema cravará exatamente 10 sucessos e barrará 990 tentativas, provando a eficácia da proteção atômica e o isolamento contra Race Conditions.