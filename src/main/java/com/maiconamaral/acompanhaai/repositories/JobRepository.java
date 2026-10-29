package com.maiconamaral.acompanhaai.repositories;
import com.maiconamaral.acompanhaai.entities.Job;
import org.springframework.data.jpa.repository.JpaRepository;
public interface JobRepository extends JpaRepository<Job, Long> { }
