package com.joaoricardo.ticketflow.domain.entity;
import jakarta.persistence.*;
import lombok.*;

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
