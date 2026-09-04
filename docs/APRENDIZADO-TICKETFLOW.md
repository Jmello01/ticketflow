# TicketFlow — Guia de Estudo Linha a Linha

> Documento de estudo do projeto `ticketflow` (github.com/Jmello01/ticketflow-simulator), feito para consolidar o aprendizado sobre concorrência em Java/Spring Boot e servir de base para posts técnicos no LinkedIn.

## 1. Visão geral: o problema e a arquitetura

O projeto resolve um problema clássico: **1000 pessoas competindo por 10 ingressos ao mesmo tempo**, sem vender "ingressos fantasmas" (mais do que o estoque real).

Fluxo desenhado:

```
Usuário → API Spring Boot → Redis (validação atômica) → RabbitMQ (fila) → Consumer → Banco de Dados
```

A ideia central é **nunca deixar o banco de dados (lento, com lock pesado) ser o primeiro a decidir se há estoque**. Quem decide isso é o Redis, porque a operação `DECR` dele é atômica em nível de sistema — não existe condição de corrida ali, mesmo com milhares de threads batendo ao mesmo tempo. O banco só entra depois, de forma assíncrona, via fila, para persistir o resultado com calma.

Isso já é a primeira lição de arquitetura: **cada componente faz o que sabe fazer melhor** — Redis é rápido e atômico, mas não é durável; RabbitMQ absorve pico de carga (*load leveling*); o banco garante durabilidade, mas é lento sob concorrência pesada.

---

## 2. Estrutura de pacotes

```
com.joaoricardo.ticketflow
├── TicketflowApplication.java        (ponto de entrada)
├── domain
│   ├── controller/TicketController.java
│   ├── dto/SimulationResult.java
│   ├── entity/Event.java
│   ├── exception/TicketStockException.java
│   └── repository/EventRepository.java
├── infrastructure
│   ├── GlobalExceptionHandler.java
│   ├── RabbitConfig.java
│   └── TicketConsumer.java
└── service
    └── TicketService.java
```

Essa separação (`domain` / `infrastructure` / `service`) é um mini exemplo de **arquitetura em camadas**: `domain` guarda as regras e os contratos do negócio (entidades, DTOs, exceções, repositório), `infrastructure` guarda o que conversa com o mundo externo (fila, tratamento global de erro), e `service` guarda a lógica de aplicação que orquestra tudo. É um passo na direção de Clean Architecture / Hexagonal, embora ainda simplificado — mais sobre isso na seção de melhorias.

---

## 3. `TicketflowApplication.java` — o ponto de entrada

```java
@SpringBootApplication
@EnableRetry // Sem isso, o @Retryable do Service é ignorado
public class TicketflowApplication {
    public static void main(String[] args) {
        SpringApplication.run(TicketflowApplication.class, args);
    }
}
```

- **`@SpringBootApplication`**: é uma anotação "combo" — por baixo dos panos ela junta três outras: `@Configuration` (essa classe pode declarar beans), `@EnableAutoConfiguration` (o Spring tenta configurar sozinho tudo que encontra no classpath — por isso basta adicionar a dependência do Redis no `pom.xml` para o Spring já criar o `StringRedisTemplate` pronto para uso) e `@ComponentScan` (o Spring varre o pacote atual e os subpacotes procurando classes anotadas com `@Component`, `@Service`, `@RestController`, etc., para registrar como *beans* gerenciados).
- **`@EnableRetry`**: liga o mecanismo de retry declarativo do Spring (`spring-retry`). O comentário no código já avisa: **sem essa anotação, qualquer `@Retryable` em qualquer `@Service` do projeto simplesmente não funciona** — o método roda normalmente, sem nenhuma tentativa extra. É importante guardar esse detalhe porque, como você vai ver na seção 9, o `TicketService` importa `@Retryable` mas **não o usa** — então essa anotação hoje está "pronta e ligada", mas sem nenhum método consumindo ela.
- **`SpringApplication.run(...)`**: sobe o contexto do Spring (cria todos os beans, injeta dependências, inicia o Tomcat embutido na porta 8080).

---

## 4. `Event.java` — a entidade JPA

```java
@Entity
@Getter @Setter
@NoArgsConstructor
public class Event {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String name;
    private Integer availableTickets;

    @Version
    private Long version; // o segredo da concorrência
}
```

- **`@Entity`**: diz ao Hibernate (a implementação de JPA usada pelo Spring Data JPA) "esta classe vira uma tabela no banco".
- **`@Getter @Setter @NoArgsConstructor`** (Lombok): geram em tempo de compilação os métodos `getId()`, `setId()`, etc., e um construtor vazio. JPA **exige** um construtor sem argumentos porque é assim que o Hibernate instancia o objeto antes de popular os campos via reflexão.
- **`@Id` + `@GeneratedValue(strategy = GenerationType.IDENTITY)`**: a chave primária é gerada pelo próprio banco (auto-incremento), não pela aplicação.
- **`@Version`**: essa é a linha mais importante do arquivo, e o comentário do próprio código ("o segredo da concorrência") está certo. É o mecanismo de **Optimistic Locking** (bloqueio otimista) do JPA/Hibernate.

  Como funciona na prática: toda vez que você lê um `Event`, o Hibernate também lê o valor de `version` (ex: `version = 5`). Quando você chama `repository.save(event)`, o Hibernate gera um `UPDATE ... WHERE id = ? AND version = 5`. Se, entre a leitura e a escrita, outra thread já tiver salvo o mesmo registro (e o `version` no banco já for `6`), esse `UPDATE` **não afeta nenhuma linha** — e o Hibernate detecta isso e lança `ObjectOptimisticLockingFailureException`.

  Isso é "otimista" porque **não trava nada durante a leitura** (diferente de um lock pessimista, que usaria `SELECT ... FOR UPDATE` e travaria a linha para outras transações). Ele aposta que colisões são raras e só lida com o conflito quando ele de fato acontece. É uma escolha excelente para leitura frequente e escrita esporádica — mas, como você vai ver, o projeto hoje **detecta** o conflito mas não faz nada de útil com ele (não há retry).

---

## 5. `SimulationResult.java` — um `record`

```java
public record SimulationResult(
        int totalAttempts,
        int successfulPurchases,
        int failures,
        long executionTimeMs,
        List<String> logs
) {}
```

`record` é um recurso do Java moderno (desde o Java 16) feito exatamente para **DTOs imutáveis**. Uma linha como essa substitui ~40 linhas de Java "clássico": o compilador gera automaticamente construtor, getters (aqui chamados `totalAttempts()`, sem o prefixo `get`), `equals()`, `hashCode()` e `toString()`. Como é imutável (todos os campos são `final` por baixo dos panos), é uma ótima escolha para representar "uma fotografia de um resultado" — não faz sentido alguém mudar o `totalAttempts` depois que a simulação já terminou.

---

## 6. `TicketStockException.java` e `GlobalExceptionHandler.java`

```java
public class TicketStockException extends RuntimeException {
    public TicketStockException(String message) { super(message); }
}
```

Uma exceção de domínio customizada. Estender `RuntimeException` (e não `Exception`) significa que ela é **unchecked** — quem chama o método não é obrigado a declarar `throws` nem envolver em `try/catch`. Isso é uma escolha de estilo comum em projetos Spring modernos: erros de regra de negócio (como "estoque esgotado") geralmente não são recuperáveis pelo chamador imediato, então faz mais sentido deixá-los subir até uma camada central de tratamento — que é exatamente o papel da próxima classe.

```java
@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(TicketStockException.class)
    public ResponseEntity<?> handleStock(TicketStockException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("erro", ex.getMessage()));
    }
}
```

- **`@RestControllerAdvice`**: é um interceptador global — funciona para *todos* os `@RestController` da aplicação, não só um.
- **`@ExceptionHandler(TicketStockException.class)`**: sempre que essa exceção específica escapar de qualquer controller, o Spring intercepta e chama este método em vez de devolver um erro 500 genérico.
- **`HttpStatus.CONFLICT` (409)**: escolha semântica correta de status HTTP — "o estado atual do recurso (estoque) conflita com a operação pedida". Vale notar: hoje só existe um `@ExceptionHandler`; qualquer outra exceção não tratada (ex: `NullPointerException`) ainda cai no handler padrão do Spring (500 genérico, com stacktrace exposto se o `management.endpoints` estiver liberado — ver seção 10).

---

## 7. `EventRepository.java` — Spring Data JPA

```java
public interface EventRepository extends JpaRepository<Event, Long> {}
```

Essa interface **vazia** já ganha, de graça, métodos como `save()`, `findById()`, `findAll()`, `deleteById()`, etc. O Spring Data JPA gera a implementação em tempo de execução via *proxy dinâmico* — você nunca escreve o `EventRepositoryImpl`. `<Event, Long>` diz "a entidade é `Event`, e o tipo da chave primária é `Long`".

---

## 8. `RabbitConfig.java` e `TicketConsumer.java` — a fila

```java
@Configuration
public class RabbitConfig {
    public static final String QUEUE_NAME = "tickets.queue";

    @Bean
    public Queue ticketQueue() {
        return new Queue(QUEUE_NAME, true);
    }
}
```

- **`@Configuration`**: classe que declara *beans* manualmente (em vez de usar `@Component` com scan automático).
- **`@Bean`**: o método `ticketQueue()` retorna um objeto que o Spring vai gerenciar e registrar. Aqui ele garante que a fila `tickets.queue` **existe** no RabbitMQ assim que a aplicação sobe (se não existir, ele a cria).
- **`new Queue(QUEUE_NAME, true)`**: o segundo parâmetro (`true`) é `durable` — a fila sobrevive a um restart do RabbitMQ. Isso é importante: se você reiniciar o broker no meio de uma simulação, mensagens não commitadas não seriam perdidas *desde que* também sejam publicadas como persistentes (isso é configuração adicional que o projeto ainda não faz explicitamente — ver melhorias).

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class TicketConsumer {
    private final EventRepository repository;
    private final MeterRegistry registry;

    @RabbitListener(queues = RabbitConfig.QUEUE_NAME)
    @Transactional
    public void handleTicketPurchase(Long eventId) {
        try {
            var event = repository.findById(eventId)
                    .orElseThrow(() -> new RuntimeException("Evento não encontrado"));

            if (event.getAvailableTickets() > 0) {
                event.setAvailableTickets(event.getAvailableTickets() - 1);
                repository.save(event);
                registry.counter("tickets.sold.database").increment();
                log.info("[DATABASE] Ingresso processado com sucesso!");
            }
        } catch (Exception e) {
            log.error("Erro ao processar venda: {}", e.getMessage());
        }
    }
}
```

- **`@RequiredArgsConstructor`** (Lombok): gera um construtor com todos os campos `final` como parâmetros. É assim que o Spring faz **injeção de dependência via construtor** sem você escrever o construtor à mão — a forma recomendada hoje em dia (em vez de `@Autowired` em campo).
- **`@Slf4j`** (Lombok): injeta automaticamente um campo estático `log` (do SLF4J) na classe, sem você declarar `private static final Logger log = LoggerFactory.getLogger(...)`.
- **`@RabbitListener(queues = ...)`**: registra este método como *consumidor* da fila. Toda mensagem publicada em `tickets.queue` (um `Long eventId`, serializado automaticamente) dispara esta execução.
- **`@Transactional`**: abre uma transação de banco para o método inteiro — se algo falhar no meio, o `UPDATE` é revertido (rollback).
- A lógica interna é um clássico **read-modify-write**: lê o evento, verifica estoque, decrementa, salva. Isso é seguro aqui por uma razão que **não está explícita no código**: por padrão, o Spring AMQP processa mensagens de uma fila **uma de cada vez, em uma única thread de consumo** (`concurrentConsumers` default = 1). Se alguém aumentar a concorrência do listener no futuro (uma otimização razoável para dar mais throughput), esse código passaria a ter uma condição de corrida real — duas threads consumidoras lendo o mesmo `Event`, ambas vendo `availableTickets > 0`, ambas decrementando a partir do mesmo valor. O `@Version` da entidade protegeria isso lançando `ObjectOptimisticLockingFailureException` — mas repare que o `catch (Exception e)` aqui **engole silenciosamente** esse erro só logando, sem devolver o ticket para o Redis. Isso é uma pegadinha real de concorrência (ver seção 9).

---

## 9. `TicketService.java` — o coração da lógica de negócio (e onde estão os bugs)

```java
@Service
@RequiredArgsConstructor
public class TicketService {
    private final EventRepository repository;
    private final StringRedisTemplate redisTemplate;

    @Transactional
    public void purchase(Long eventId) {
        String redisKey = "event:" + eventId + ":stock";

        Long remaining = redisTemplate.opsForValue().decrement(redisKey);

        if (remaining != null && remaining < 0) {
            redisTemplate.opsForValue().increment(redisKey);
            throw new TicketStockException("Ingressos esgotados (Check by Redis)");
        }

        try {
            var event = repository.findById(eventId).orElseThrow();
            event.setAvailableTickets(event.getAvailableTickets() - 1);
            repository.save(event);
        } catch (Exception e) {
            redisTemplate.opsForValue().increment(redisKey);
            throw e;
        }
    }
}
```

- **`redisTemplate.opsForValue().decrement(redisKey)`**: chama o comando `DECR` do Redis. Esse comando é **atômico no servidor Redis** — mesmo que 1000 requisições cheguem "ao mesmo tempo", o Redis é single-threaded para operações de escrita e as executa em sequência, então nunca duas threads decrementam "por cima uma da outra". É por isso que essa linha, sozinha, já resolve o problema de "vender mais do que existe" — o resto da arquitetura (fila, banco) existe para durabilidade e desempenho, não para a correção da contagem em si.
- **Padrão "compensação" (`increment` de volta)**: se o Redis acusar estoque negativo, ou se o banco falhar depois, o código *devolve* a unidade ao Redis. Isso é uma forma manual de manter o Redis (que não participa de transações de banco) consistente com o resultado real — um mini padrão de **Saga/compensating transaction**.
- **O bug real**: repare que a classe **importa** `org.springframework.retry.annotation.Backoff` e `org.springframework.retry.annotation.Retryable`, mas **nenhuma anotação `@Retryable` está de fato aplicada** ao método `purchase()`. Isso, combinado com o `@EnableRetry` da classe principal (que só liga o mecanismo, não cria retries sozinho), significa que **hoje não existe nenhuma tentativa automática de novo** quando a gravação no banco falha por `ObjectOptimisticLockingFailureException`. O comentário `// o segredo da concorrência` no `Event.java` e o nome do teste `testConcurrencyWithRetry` sugerem que a intenção original era ter algo como:

  ```java
  @Retryable(
      retryFor = ObjectOptimisticLockingFailureException.class,
      maxAttempts = 3,
      backoff = @Backoff(delay = 50)
  )
  @Transactional
  public void purchase(Long eventId) { ... }
  ```

  Sem isso, se dois dos "10 vencedores" do Redis colidirem na hora de salvar no banco (leem a mesma `version`, um salva e o outro recebe `ObjectOptimisticLockingFailureException`), o segundo **não tenta de novo** — ele cai no `catch`, devolve o ticket ao Redis, e a exceção sobe. O resultado seria **menos de 10 vendas confirmadas no banco**, mesmo o Redis tendo liberado exatamente 10. Isso é sutil porque só aparece sob concorrência real (múltiplas threads no mesmo `Event`), não em testes sequenciais — e é exatamente o tipo de bug que faz um projeto "funcionar na demonstração" mas falhar de vez em quando em produção.

- **Imports duplicados**: `StringRedisTemplate`, `Service` e `Transactional` estão importados duas vezes cada um (resultado provável de edições incrementais com autocompletar). Não quebra a compilação, mas é um sinal de "código que precisa de uma limpeza" antes de ir para o LinkedIn como exemplo de qualidade.

---

## 10. `TicketController.java` — o problema mais importante do projeto

```java
@PostMapping("/run")
public SimulationResult runSimulation(@RequestParam int threads, @RequestParam int tickets) throws InterruptedException {
    Event event = new Event();
    event.setName("Simulação Concorrente");
    event.setAvailableTickets(tickets);
    event = repository.save(event);

    Long eventId = event.getId();
    redisTemplate.opsForValue().set("event:" + eventId + ":stock", String.valueOf(tickets));

    ExecutorService executor = Executors.newFixedThreadPool(threads);
    CountDownLatch latch = new CountDownLatch(1);
    List<String> logs = Collections.synchronizedList(new ArrayList<>());

    for (int i = 0; i < threads; i++) {
        int threadId = i;
        executor.execute(() -> {
            try {
                latch.await();
                Long remaining = redisTemplate.opsForValue().decrement("event:" + eventId + ":stock");
                if (remaining != null && remaining >= 0) {
                    rabbitTemplate.convertAndSend(RabbitConfig.QUEUE_NAME, eventId);
                    successCount.incrementAndGet();
                    logs.add("Pedido enviado para a fila!");
                } else {
                    registry.counter("tickets.rejected.redis").increment();
                    logs.add("Thread-" + threadId + ": Esgotado no Redis.");
                }
            } catch (Exception e) {
                logs.add("Erro: " + e.getMessage());
            }
        });
    }
    latch.countDown();
    executor.shutdown();
    executor.awaitTermination(10, TimeUnit.SECONDS);
    ...
}
```

**Este é o achado mais importante da revisão.** Repare: o endpoint `/run` **não chama `ticketService.purchase(...)` em nenhum momento**. Ele reimplementa, do zero e diretamente no controller, a mesma lógica de decremento do Redis + envio para a fila. Isso quer dizer que:

1. Existem hoje **dois caminhos de compra diferentes** no sistema: o "oficial" (`TicketService.purchase`, chamado pelo teste `TicketConcurrencyTest`) e o "de demonstração" (dentro do controller, usado pelo endpoint que o dashboard realmente chama). Eles têm lógica parecida mas não idêntica — por exemplo, o controller nunca toca no banco diretamente (delega 100% ao `TicketConsumer` via fila), enquanto o `TicketService` escreve no banco de forma síncrona dentro da própria chamada.
2. Isso viola o princípio **DRY (Don't Repeat Yourself)** e, pior, quebra a garantia real de teste: quando você "prova" a robustez do sistema rodando a simulação pelo dashboard (`/api/simulation/run`), você **não está testando o `TicketService`** — está testando um código-caminho paralelo que só existe no controller. Se um dia você corrigir o bug do `@Retryable` (seção 9) só no `TicketService`, o endpoint que o recrutador realmente vai clicar no dashboard **não vai se beneficiar da correção**.
3. Do ponto de vista de arquitetura em camadas, um `@RestController` **não deveria conter lógica de negócio** (decisão de decrementar estoque, decisão de publicar na fila) — essa é a responsabilidade do `service`. O controller deveria só orquestrar: receber a requisição HTTP, chamar o service N vezes (ou delegar a orquestração da simulação para uma classe própria, tipo `SimulationRunner`), e devolver a resposta.

Outros pontos desse arquivo:

- **`ExecutorService executor = Executors.newFixedThreadPool(threads)`**: cria um pool de threads de **plataforma** (as "pesadas", do sistema operacional) — não Virtual Threads. O README menciona Virtual Threads como parte da arquitetura, e o `application.properties` até ativa `spring.threads.virtual.enabled=true` (que afeta as threads que atendem requisições HTTP no Tomcat), mas **este `ExecutorService` específico, usado para gerar a carga da simulação, não usa Virtual Threads** — seria `Executors.newVirtualThreadPerTaskExecutor()`. Vale ajustar para bater com o que o README promete.
- **`CountDownLatch latch = new CountDownLatch(1)`**: um truque clássico para "largada simultânea". Todas as threads são criadas e ficam bloqueadas em `latch.await()`; só depois que todas as `threads` threads foram submetidas ao executor é que `latch.countDown()` libera todas de uma vez, maximizando a chance real de colisão concorrente (em vez de threads começarem em momentos ligeiramente diferentes conforme são criadas).
- **`AtomicInteger successCount`**: um contador seguro para concorrência sem precisar de `synchronized`. Internamente usa operações **CAS (Compare-And-Swap)** em vez de locks — mais rápido sob alta contenção.
- **`Collections.synchronizedList(new ArrayList<>())`**: envolve a lista para tornar `add()` thread-safe. Vale notar que **iterar** sobre uma `synchronizedList` ainda não é automaticamente seguro (precisaria de `synchronized` manual no bloco de iteração) — aqui não há iteração concorrente, então está correto, mas é um detalhe importante de Java a guardar.
- **`GET /api/simulation/reset`**: hoje reseta o Redis sempre para `"10"`, fixo (`redisTemplate.opsForValue().set(..., "10")`) — não usa nenhum parâmetro de quantidade. Funciona só porque o dashboard sempre testa com 10 ingressos; se algum dia você quiser resetar para outro valor, o endpoint mente sobre o que está fazendo. Um `@RequestParam(defaultValue = "10") int tickets` resolveria.

---

## 11. Os testes

**`TicketflowApplicationTests.java`** — teste "esqueleto" padrão do Spring Boot (`contextLoads()`), só garante que o contexto sobe sem erro. Não testa nenhuma regra de negócio.

**`TicketValidatorTest.java`** — um ponto de atenção pedagógico importante: os 7 testes aqui **não chamam nenhuma classe real do projeto**. Eles fazem coisas como:
```java
int estoque = 100;
int quantidadeSolicitada = 50;
boolean ehValido = quantidadeSolicitada <= estoque;
assertThat(ehValido).isTrue();
```
Isso testa a *expressão booleana escrita ali mesmo no teste*, não uma função do `TicketService` ou do `Event`. Ou seja: se alguém introduzir um bug real na validação de estoque dentro do `TicketService`, **esses testes continuam passando** — porque eles não exercitam o código de produção, só reafirmam que `<=` funciona em Java. É um padrão bem comum ("testes de papel") que parece dar cobertura, mas na prática só testa a JVM. Um bom próximo passo de portfólio: extrair a regra de validação para um método real (`TicketValidator.isValid(estoque, quantidade)`) e fazer esses mesmos testes chamarem esse método.

**`TicketConcurrencyTest.java`** — este sim é um teste de integração de verdade: sobe o contexto Spring completo, cria um evento com 10 ingressos, dispara 50 threads reais chamando `ticketService.purchase(eventId)` simultaneamente (com o mesmo truque de `CountDownLatch` do controller) e verifica que exatamente 10 tiveram sucesso e o estoque final é 0. É o teste mais valioso do projeto — mas, como vimos na seção 9, ele é exatamente o tipo de teste que ficaria **flaky** (às vezes passa, às vezes falha) se a colisão de `@Version` no banco acontecer com frequência suficiente e não houver retry.

---

## 12. Resumo dos problemas encontrados (priorizados)

1. **Falta `@Retryable` em `TicketService.purchase()`** — a proteção contra `ObjectOptimisticLockingFailureException` no banco existe apenas na entidade (`@Version`), mas nada tenta de novo quando ela ocorre. Import morto de `Retryable`/`Backoff`.
2. **`TicketController.runSimulation` duplica a lógica de `TicketService.purchase`** em vez de chamá-la — dois caminhos de compra divergentes no mesmo sistema.
3. **`TicketConsumer.handleTicketPurchase` engole exceções silenciosamente** (`catch (Exception e) { log.error(...) }`) sem devolver o ticket ao Redis em caso de falha — se o `save()` falhar, o Redis fica "achando" que vendeu um ingresso que nunca foi persistido no banco.
4. **`GET /reset` tem o valor `"10"` hardcoded** em vez de receber a quantidade como parâmetro.
5. **`ExecutorService` da simulação usa threads de plataforma**, não Virtual Threads — inconsistente com o que o README anuncia.
6. **`TicketValidatorTest` não testa código de produção** — são "testes de papel" que dão falsa sensação de cobertura.
7. **Imports duplicados** em `TicketService.java` (limpeza cosmética).

Esses 7 pontos, na ordem certa, viram um roteiro perfeito de commits/PRs — e cada um é, sozinho, conteúdo suficiente para um post técnico no LinkedIn (ex: "Encontrei um bug de concorrência silencioso no meu próprio projeto — e é assim que eu corrigi").

---

## 13. Conceitos de Java para revisar a partir deste projeto

Se você quer generalizar o aprendizado (não só entender este projeto, mas Java como um todo), estes são os tópicos que o TicketFlow toca e vale estudar a fundo, em ordem de "voltagem" para entrevistas de vaga Java:

- **Concorrência**: `Thread`, `ExecutorService`, `Executors.newFixedThreadPool` vs `newVirtualThreadPerTaskExecutor`, `CountDownLatch`, `AtomicInteger`/CAS, `synchronized`, `Collections.synchronizedList`, e por que Virtual Threads (Java 21, *Project Loom*) mudam o cálculo de custo de concorrência em código I/O-bound.
- **JPA/Hibernate**: ciclo de vida de entidade, Optimistic vs Pessimistic Locking, `@Transactional` (o que é uma transação, isolation levels, quando o Spring faz rollback automático — só em `RuntimeException` por padrão!).
- **Spring**: injeção de dependência (construtor vs campo), `@Bean` vs `@Component`, `@RestControllerAdvice`, ciclo de vida de uma requisição HTTP no Spring MVC.
- **Lombok**: o que cada anotação gera de fato (vale compilar uma vez e olhar o `.class` decompilado para "ver" o código gerado).
- **Records e imutabilidade**: por que DTOs imutáveis evitam uma classe inteira de bugs de concorrência.
- **Exceções checked vs unchecked**: quando usar cada uma, e como isso afeta contratos de API.
- **Mensageria**: at-least-once vs exactly-once delivery, idempotência de consumidores (pergunta boa de entrevista: "o que acontece se essa mensagem for processada duas vezes?" — hoje o `TicketConsumer` **não é idempotente**, rodar a mesma mensagem duas vezes decrementaria o estoque duas vezes).

---

## 14. Ideias de posts para o LinkedIn

1. **"Achei um bug de concorrência no meu próprio projeto de portfólio"** — conte a descoberta do `@Retryable` ausente, explique Optimistic Locking com uma analogia simples, mostre o antes/depois.
2. **"Redis não é só cache: usando DECR atômico para resolver race conditions"** — explique por que a operação atômica do Redis resolve o problema antes mesmo do banco entrar em cena.
3. **"Por que meu controller estava 'mentindo' sobre qual código eu estava testando"** — sobre a duplicação de lógica entre `TicketController` e `TicketService`, e a lição de manter um único caminho de verdade (*single source of truth*) para regras de negócio.
4. **"Testes que não testam nada: o que aprendi revisando minha própria suíte de testes"** — sobre o `TicketValidatorTest` ser "teste de papel", e como migrar para testar o código de produção de verdade.

---

## 15. Próximos passos sugeridos (curto prazo)

1. Adicionar `@Retryable(retryFor = ObjectOptimisticLockingFailureException.class, maxAttempts = 3, backoff = @Backoff(delay = 50))` em `TicketService.purchase()`.
2. Refatorar `TicketController.runSimulation` para chamar `ticketService.purchase(eventId)` dentro do loop de threads, removendo a lógica duplicada.
3. Fazer `TicketConsumer` devolver o ticket ao Redis (`increment`) quando o `save()` falhar, e considerar idempotência (ex: verificar se a mensagem já foi processada antes de decrementar).
4. Trocar `Executors.newFixedThreadPool(threads)` por `Executors.newVirtualThreadPerTaskExecutor()` na simulação.
5. Reescrever `TicketValidatorTest` para chamar métodos reais do domínio.
6. Parametrizar o `/reset` e limpar os imports duplicados de `TicketService`.

Cada um desses itens pode ser feito como um commit isolado com mensagem clara — ótimo tanto para o histórico do GitHub quanto para servir de "antes e depois" nos posts.
