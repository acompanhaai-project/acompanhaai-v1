package com.maiconamaral.acompanhaai.services;

import com.maiconamaral.acompanhaai.dto.*;
import com.maiconamaral.acompanhaai.entities.*;
import com.maiconamaral.acompanhaai.entities.enums.ApplicationStatus;
import com.maiconamaral.acompanhaai.repositories.*;
import com.maiconamaral.acompanhaai.services.exceptions.*;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Stream;
import org.springframework.data.domain.*;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ApplicationService {

    private final ApplicationRepository repository;
    private final JobRepository jobs;
    private final UserRepository users;
    private final ApplicationStatusHistoryRepository history;
    private final JobUrlService urls;

    public ApplicationService(
        ApplicationRepository repository,
        JobRepository jobs,
        UserRepository users,
        ApplicationStatusHistoryRepository history,
        JobUrlService urls
    ) {
        this.repository = repository;
        this.jobs = jobs;
        this.users = users;
        this.history = history;
        this.urls = urls;
    }

    @Transactional
    public ApplicationDTO insert(Long userId, ApplicationInsertDTO dto) {
        var status = dto.status() == null ? ApplicationStatus.APPLIED : dto.status();
        validate(status, dto.appliedOn());
        validateJob(dto.job());
        var user = lockUser(userId);
        var job = new Job(user, dto.job());
        assignUrl(userId, job, dto.job().sourceUrl());
        jobs.save(job);
        var application = repository.saveAndFlush(new Application(user, job, status, dto.appliedOn(), dto.notes()));
        history.save(new ApplicationStatusHistory(application, null));
        return new ApplicationDTO(application);
    }

    @Transactional(readOnly = true)
    public PageDTO<ApplicationDTO> findAll(Long userId, ApplicationStatus status, String query, int page, int size) {
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.desc("id")));
        var result = repository.search(userId, status, searchPattern(query), pageable);
        return new PageDTO<>(result.map(ApplicationDTO::new));
    }

    @Transactional(readOnly = true)
    public ApplicationDTO findById(Long userId, Long id) {
        return new ApplicationDTO(findEntity(userId, id));
    }

    @Transactional
    public ApplicationDTO update(Long userId, Long id, ApplicationUpdateDTO dto) {
        lockUser(userId);
        var application = findEntity(userId, id);
        if (application.getVersion() != dto.version()) {
            throw new ObjectOptimisticLockingFailureException(Application.class, id);
        }
        validate(dto.status(), dto.appliedOn());
        validateJob(dto.job());
        var previous = application.getStatus();
        assignUrl(userId, application.getJob(), dto.job().sourceUrl());
        application.getJob().update(dto.job());
        application.update(dto.status(), dto.appliedOn(), dto.notes());
        repository.saveAndFlush(application);
        if (previous != dto.status()) {
            history.save(new ApplicationStatusHistory(application, previous));
        }
        return new ApplicationDTO(application);
    }

    @Transactional(readOnly = true)
    public List<ApplicationHistoryDTO> history(Long userId, Long id) {
        findEntity(userId, id);
        return history
            .findByApplication_IdOrderByChangedAtAscIdAsc(id)
            .stream()
            .map(ApplicationHistoryDTO::new)
            .toList();
    }

    @Transactional
    public void delete(Long userId, Long id) {
        lockUser(userId);
        var application = findEntity(userId, id);
        var job = application.getJob();
        repository.delete(application);
        repository.flush();
        jobs.delete(job);
    }

    private User lockUser(Long userId) {
        return users
            .findByIdForUpdate(userId)
            .orElseThrow(() -> new ResourceNotFoundException("Usuário não encontrado."));
    }

    private void assignUrl(Long userId, Job job, String sourceUrl) {
        String normalized = urls.normalize(sourceUrl);
        String hash = urls.hash(normalized);
        if (normalized != null) {
            for (var candidate : jobs.findUrlCandidates(userId)) {
                if (Objects.equals(candidate.id(), job.getId())) {
                    continue;
                }
                String existing;
                try {
                    existing = urls.normalize(candidate.sourceUrl());
                } catch (InvalidDataException exception) {
                    // Preserve malformed legacy links without blocking unrelated valid URLs.
                    existing = candidate.sourceUrl();
                }
                if (normalized.equals(existing)) {
                    throw JobUrlConflictException.duplicate();
                }
                if (hash.equals(candidate.urlHash())) {
                    throw JobUrlConflictException.hashCollision();
                }
            }
        }
        job.setNormalizedUrl(normalized);
        job.setUrlHash(hash);
    }

    private Application findEntity(Long userId, Long id) {
        return repository
            .findByIdAndUser_Id(id, userId)
            .orElseThrow(() -> new ResourceNotFoundException("Candidatura não encontrada."));
    }

    private String searchPattern(String query) {
        if (query == null || query.isBlank()) {
            return "%";
        }
        String escaped = query.strip().replace("!", "!!").replace("%", "!%").replace("_", "!_");
        return "%" + escaped + "%";
    }

    private void validate(ApplicationStatus status, LocalDate appliedOn) {
        if (status == ApplicationStatus.SAVED && appliedOn != null) {
            throw new InvalidDataException("Uma vaga apenas salva não pode ter data de candidatura.");
        }
    }

    private void validateJob(JobInputDTO dto) {
        if (dto.salary() == null) {
            if (dto.salaryCurrency() != null || dto.salaryPeriod() != null) {
                throw new InvalidDataException("Informe um salário para definir moeda e periodicidade.");
            }
        } else if (!"BRL".equals(dto.salaryCurrency()) || dto.salaryPeriod() == null) {
            throw new InvalidDataException("Informe moeda BRL e periodicidade para o salário.");
        }
        var names = new HashSet<String>();
        for (String name : Stream.concat(dto.hardSkills().stream(), dto.softSkills().stream()).toList()) {
            String normalized = name.toLowerCase(Locale.ROOT);
            if (normalized.length() > 150) {
                throw new InvalidDataException("O nome normalizado da habilidade deve ter até 150 caracteres.");
            }
            if (!names.add(normalized)) {
                throw new InvalidDataException("Não são permitidas habilidades duplicadas, inclusive entre tipos.");
            }
        }
    }
}
