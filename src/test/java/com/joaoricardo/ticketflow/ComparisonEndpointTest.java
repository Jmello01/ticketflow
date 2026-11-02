package com.joaoricardo.ticketflow;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Passa pela requisição HTTP, onde o contexto de persistência fica aberto durante toda a chamada. */
@SpringBootTest
@AutoConfigureMockMvc
class ComparisonEndpointTest {

    @Autowired private MockMvc mvc;

    @Test
    void estoqueNoBancoRefleteAsVendasMesmoDentroDeUmaRequisicao() throws Exception {
        mvc.perform(post("/api/simulation/compare").param("threads", "200").param("tickets", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.strategies[1].id").value("optimistic"))
                .andExpect(jsonPath("$.strategies[1].sold").value(10))
                .andExpect(jsonPath("$.strategies[1].databaseStock").value(0))
                .andExpect(jsonPath("$.strategies[2].id").value("redis"))
                .andExpect(jsonPath("$.strategies[2].databaseStock").value(0));
    }

    @Test
    void rejeitaParametrosInvalidos() throws Exception {
        mvc.perform(post("/api/simulation/compare").param("threads", "0").param("tickets", "10"))
                .andExpect(status().isBadRequest());
    }
}
