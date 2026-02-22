# 🎟️ TicketFlow - Distributed Concurrency Lab

Este projeto aplica conceitos fundamentais de **Sistemas Operacionais** e **Sistemas Distribuídos** para resolver o problema de disputa de recursos em aplicações Spring Boot.

##  Conceitos de SO Aplicados
* **Race Conditions:** Tratamento de condições de corrida em operações de decremento de estoque.
* **Barreiras de Sincronização:** Uso de `CountDownLatch` para testes de concorrência paralela.
* **Optimistic Locking:** Implementação de controle de concorrência via versão (MVCC principle) para evitar deadlocks e overhead de locks pessimistas.

##  Stack Técnica
* **Linguagem:** Java 21 (Loom-ready thinking).
* **Framework:** Spring Boot 3.x + Spring Data JPA.
* **Resiliência:** Spring Retry com Exponential Backoff.
* **Banco de Dados:** H2 (In-memory para testes rápidos).

## Resultado do Teste de Estresse
O sistema foi submetido a 50 requisições simultâneas concorrendo por 10 recursos.
* **Sucessos:** 10 (Integridade total).
* **Falhas recuperadas:** 40 (Tratadas via exceção de negócio).
* **Estado final do banco:** Consistente (Estoque = 0).