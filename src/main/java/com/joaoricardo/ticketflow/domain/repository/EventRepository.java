package com.joaoricardo.ticketflow.domain.repository;

import com.joaoricardo.ticketflow.domain.entity.Event;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EventRepository extends JpaRepository<Event, Long> {}
