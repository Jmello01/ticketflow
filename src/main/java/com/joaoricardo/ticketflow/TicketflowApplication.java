package com.joaoricardo.ticketflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.retry.annotation.EnableRetry; // IMPORTANTE

@SpringBootApplication
@EnableRetry // Sem isso, o @Retryable do Service é ignorado
public class TicketflowApplication {

	public static void main(String[] args) {
		SpringApplication.run(TicketflowApplication.class, args);
	}

}
