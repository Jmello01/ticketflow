package com.joaoricardo.ticketflow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;

@DisplayName("Validador de Estoque de Ingresso")
public class TicketValidatorTest {
    @Test
    @DisplayName("Deve validar quando quantidade < estoque")
    void deveValidarQuantidadeDisponivel() {
        // arrange
        int estoque = 100;
        int quantidadeSolicitada = 50;

        //act
        boolean ehValido = quantidadeSolicitada <= estoque;

        // assert
        assertThat(ehValido).as("Deve permitir compra de 50 ingressos quando há 100 disponíveis")
                .isTrue();
    }

    // Segundo teste: rejeitar quando o estoque for insuficiente
    @Test
    @DisplayName("Deve rejeitar quando quantidade > estoque")
    void deveRejeitarQuantidadeIndisponivel() {
        // arrange
        int estoque = 10;
        int quantidadeSolicitada = 15;

        //act
        boolean ehValido = quantidadeSolicitada <= estoque;

        // assert
        assertThat(ehValido)
                .as("Deve rejeitar compra de 15 Ingressos quando há apenas 10")
                .isFalse();
    }

    // Terceiro test: Comprar exatamente o estoque disponível
    @Test
    @DisplayName("Deve permitir comprar exatamente a quantidade em estoque")
    void devePermitirCompraExataDoEstoque() {
        // arrange
        int estoque = 25;
        int quantidadeSolicitada = 25;

        // act
        boolean ehValido = quantidadeSolicitada <= estoque;

        // assert
        assertThat(ehValido)
                .as("Deve permitir quando quantidade == estoque")
                .isTrue();
    }

    // Quarto test: Validação com estoque zero
    @Test
    @DisplayName("Deve rejeitar qualquer compra quando estoque é zero")
    void deveRejeitarComEstoqueZero() {
        // arrange
        int estoque = 0;
        int quantidadeSolicitada = 1;

        // act
        boolean ehValido = quantidadeSolicitada <= estoque;

        // assert
        assertThat(ehValido)
                .as("Deve rejeitar quando não há estoque")
                .isFalse();
    }

    // Quinto test: Rejeitar quantidade negativa (prevenção de ataque)
    @Test
    @DisplayName("Deve rejeitar quantidade negativa ou zero")
    void deveRejeitarQuantidadeNegativaOuZero() {
        // arrange
        int estoque = 100;
        int[] quantidadesInvalidas = {-5, -1, 0};

        // assert
        for (int quantidade : quantidadesInvalidas) {
            assertThat(quantidade > 0)
                    .as("Quantidade " + quantidade + " deve ser inválida")
                    .isFalse();
        }
    }

    // Sexto test: Teste Parametrizado (múltiplos valores)
    @ParameterizedTest
    @ValueSource(ints = {1, 5, 10, 50, 99})
    @DisplayName("Deve aceitar várias quantidades válidas")
    void deveValidarVariasQuantidades(int quantidade) {
        // arrange
        int estoque = 100;

        // act
        boolean ehValido = quantidade <= estoque && quantidade > 0;

        // assert
        assertThat(ehValido)
                .as("Quantidade " + quantidade + " deve ser válida com estoque de 100")
                .isTrue();
    }

    // Sétimo test: Teste de Cobertura - Todos os cenários
    @Test
    @DisplayName("Cenários completos de validação")
    void testeDeCoberturaCenarios() {
        // Cenário A: Compra normal
        assertThat(10 <= 100 && 10 > 0).isTrue();

        // Cenário B: Compra do último ingresso
        assertThat(1 <= 1 && 1 > 0).isTrue();

        // Cenário C: Tentativa inválida
        assertThat(101 <= 100).isFalse();

        // Cenário D: Tentativa com estoque zerado
        assertThat(1 <= 0).isFalse();
    }
}

