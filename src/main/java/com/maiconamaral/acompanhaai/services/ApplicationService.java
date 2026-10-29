package com.maiconamaral.acompanhaai.services;

import com.maiconamaral.acompanhaai.dto.*;
import com.maiconamaral.acompanhaai.entities.*;
import com.maiconamaral.acompanhaai.entities.enums.ApplicationStatus;
import com.maiconamaral.acompanhaai.repositories.*;
import com.maiconamaral.acompanhaai.services.exceptions.*;
import org.springframework.data.domain.*;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.util.List;

@Service
public class ApplicationService {
    private final ApplicationRepository repository;
    private final JobRepository jobs;
    private final UserRepository users;
    private final ApplicationStatusHistoryRepository history;

    public ApplicationService(ApplicationRepository repository, JobRepository jobs, UserRepository users,
                               ApplicationStatusHistoryRepository history) {
        this.repository = repository;
        this.jobs = jobs;
        this.users = users;
        this.history = history;
    }

    @Transactional
    public ApplicationDTO insert(Long userId, ApplicationInsertDTO dto) {
        var status = dto.status() == null ? ApplicationStatus.APPLIED : dto.status();
        validate(status, dto.appliedOn());
        var user = users.getReferenceById(userId);
        var job = jobs.save(new Job(user, dto.job()));
        var application = repository.saveAndFlush(new Application(user, job, status, dto.appliedOn(), dto.notes()));
        history.save(new ApplicationStatusHistory(application, null));
        return new ApplicationDTO(application);
    }

    @Transactional(readOnly = true)
    public PageDTO<ApplicationDTO> findAll(Long userId, ApplicationStatus status, int page, int size) {
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.desc("id")));
        var result = status == null ? repository.findByUser_Id(userId, pageable)
                : repository.findByUser_IdAndStatus(userId, status, pageable);
        return new PageDTO<>(result.map(ApplicationDTO::new));
    }

    @Transactional(readOnly = true)
    public ApplicationDTO findById(Long userId, Long id) { return new ApplicationDTO(findEntity(userId, id)); }

    @Transactional
    public ApplicationDTO update(Long userId, Long id, ApplicationUpdateDTO dto) {
        var application = findEntity(userId, id);
        if (application.getVersion() != dto.version()) {
            throw new ObjectOptimisticLockingFailureException(Application.class, id);
        }
        validate(dto.status(), dto.appliedOn());
        var previous = application.getStatus();
        application.getJob().update(dto.job());
        application.update(dto.status(), dto.appliedOn(), dto.notes());
        repository.saveAndFlush(application);
        if (previous != dto.status()) history.save(new ApplicationStatusHistory(application, previous));
        return new ApplicationDTO(application);
    }

    @Transactional(readOnly = true)
    public List<ApplicationHistoryDTO> history(Long userId, Long id) {
        findEntity(userId, id);
        return history.findByApplication_IdOrderByChangedAtAscIdAsc(id).stream().map(ApplicationHistoryDTO::new).toList();
    }

    @Transactional
    public void delete(Long userId, Long id) {
        var application = findEntity(userId, id);
        var job = application.getJob();
        repository.delete(application);
        repository.flush();
        jobs.delete(job);
    }

    private Application findEntity(Long userId, Long id) {
        return repository.findByIdAndUser_Id(id, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Candidatura não encontrada."));
    }

    private void validate(ApplicationStatus status, LocalDate appliedOn) {
        if (status == ApplicationStatus.SAVED && appliedOn != null) {
            throw new InvalidDataException("Uma vaga apenas salva não pode ter data de candidatura.");
        }
    }
}
