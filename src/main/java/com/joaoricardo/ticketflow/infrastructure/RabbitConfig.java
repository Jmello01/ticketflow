package com.joaoricardo.ticketflow.infrastructure;

import org.springframework.amqp.core.Queue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;




@Configuration
public class RabbitConfig {

    // ADICIONE ESTA LINHA AQUI EMBAIXO:
    public static final String QUEUE_NAME = "tickets.queue";

    @Bean
    public Queue ticketQueue() {
        return new Queue(QUEUE_NAME, true);
    }
}