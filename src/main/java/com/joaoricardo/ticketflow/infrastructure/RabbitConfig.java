package com.joaoricardo.ticketflow.infrastructure;

import org.springframework.amqp.core.Queue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;




@Configuration
public class RabbitConfig {
    @Bean
    public Queue ticketQueue() {
        return new Queue("tickets.queue", true);
    }
}