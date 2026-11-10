package com.maiconamaral.acompanhaai.services;

import com.maiconamaral.acompanhaai.dto.ApplicationDTO;
import com.maiconamaral.acompanhaai.dto.ApplicationInsertDTO;
import com.maiconamaral.acompanhaai.dto.ImportTaskDTO;
import com.maiconamaral.acompanhaai.dto.ImportTaskInputDTO;
import com.maiconamaral.acompanhaai.dto.JobInputDTO;
import com.maiconamaral.acompanhaai.entities.ImportTask;
import com.maiconamaral.acompanhaai.entities.enums.ImportInputType;
import com.maiconamaral.acompanhaai.entities.enums.ImportStatus;
import com.maiconamaral.acompanhaai.repositories.ApplicationRepository;
import com.maiconamaral.acompanhaai.repositories.ImportTaskRepository;
import com.maiconamaral.acompanhaai.repositories.JobRepository;
import com.maiconamaral.acompanhaai.repositories.UserRepository;
import com.maiconamaral.acompanhaai.services.exceptions.ImportConflictException;
import com.maiconamaral.acompanhaai.services.exceptions.ImportLimitException;
import com.maiconamaral.acompanhaai.services.exceptions.InvalidDataException;
import com.maiconamaral.acompanhaai.services.exceptions.ResourceNotFoundException;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ImportTaskService {

    private static final Duration QUOTA_WINDOW = Duration.ofHours(1);
    private static final Logger log = LoggerFactory.getLogger(ImportTaskService.class);

    private final ImportTaskRepository tasks;
    private final UserRepository users;
    private final JobRepository jobs;
    private final ApplicationRepository applications;
    private final ApplicationService applicationService;
    private final JobUrlService urls;
    private final DatabaseClock clock;
    private final int hourlyLimit;
    private final int activeLimit;
    private final int globalActiveLimit;

    public ImportTaskService(
        ImportTaskRepository tasks,
        UserRepository users,
        JobRepository jobs,
        ApplicationRepository applications,
        ApplicationService applicationService,
        JobUrlService urls,
        DatabaseClock clock,
        @Value("${app.import-limits.per-user-hourly:30}") int hourlyLimit,
        @Value("${app.import-limits.per-user-active:5}") int activeLimit,
        @Value("${app.import-limits.global-active:200}") int globalActiveLimit
    ) {
        this.tasks = tasks;
        this.users = users;
        this.jobs = jobs;
        this.applications = applications;
        this.applicationService = applicationService;
        this.urls = urls;
        this.clock = clock;
        this.hourlyLimit = hourlyLimit;
        this.activeLimit = activeLimit;
        this.globalActiveLimit = globalActiveLimit;
    }

    @Transactional
    public ImportTaskDTO insert(Long userId, ImportTaskInputDTO dto) {
        var input = input(dto);
        // Serializa as solicitações da conta para que as cotas não sejam ultrapassadas em paralelo.
        var user = users.findByIdForUpdate(userId).orElseThrow(ImportTaskService::notFound);
        checkLimits(userId);
        var task = tasks.saveAndFlush(new ImportTask(user, dto.inputType(), input));
        return new ImportTaskDTO(task, ImportTaskQueue.RETENTION);
    }

    @Transactional(readOnly = true)
    public ImportTaskDTO findById(Long userId, Long id) {
        var task = tasks.findActive(id, userId, cutoff()).orElseThrow(ImportTaskService::notFound);
        return new ImportTaskDTO(task, ImportTaskQueue.RETENTION);
    }

    @Transactional
    public void delete(Long userId, Long id) {
        var task = tasks.lockActive(id, userId, cutoff()).orElseThrow(ImportTaskService::notFound);
        if (task.getStatus() == ImportStatus.COMPLETED) {
            throw ImportConflictException.alreadyCompleted();
        }
        tasks.delete(task);
    }

    @Transactional
    public Confirmation confirm(Long userId, Long id, ApplicationInsertDTO dto) {
        // Mesma ordem de bloqueio da criação de candidaturas: conta e depois tarefa.
        users.findByIdForUpdate(userId).orElseThrow(ImportTaskService::notFound);
        var task = tasks.lockActive(id, userId, cutoff()).orElseThrow(ImportTaskService::notFound);
        if (task.getStatus() == ImportStatus.COMPLETED) {
            var existing = applications
                .findByJob_IdAndUser_Id(task.getJob().getId(), userId)
                .orElseThrow(ImportTaskService::notFound);
            return new Confirmation(new ApplicationDTO(existing), false);
        }
        if (task.getStatus() != ImportStatus.READY_FOR_REVIEW) {
            throw ImportConflictException.notReady();
        }

        var application = applicationService.insert(userId, withSourceUrl(dto, task));
        task.setJob(jobs.getReferenceById(application.job().id()));
        task.setStatus(ImportStatus.COMPLETED);
        task.setReviewedAt(clock.now());
        task.setCollectedText(null);
        task.setExtractedData(null);
        tasks.saveAndFlush(task);
        return new Confirmation(application, true);
    }

    private String input(ImportTaskInputDTO dto) {
        if (dto.inputType() == ImportInputType.URL) {
            if (dto.text() != null) {
                throw new InvalidDataException("Envie somente a URL para importação por link.");
            }
            var url = urls.normalize(dto.url());
            if (url == null) {
                throw new InvalidDataException("Informe a URL da vaga.");
            }
            return url;
        }
        if (dto.url() != null) {
            throw new InvalidDataException("Envie somente a descrição para importação por texto.");
        }
        if (dto.text() == null || dto.text().isBlank()) {
            throw new InvalidDataException("Informe a descrição da vaga.");
        }
        return dto.text();
    }

    private static ApplicationInsertDTO withSourceUrl(ApplicationInsertDTO dto, ImportTask task) {
        var job = dto.job();
        if (job.sourceUrl() != null || task.getInputType() != ImportInputType.URL) {
            return dto;
        }
        var revised = new JobInputDTO(
            job.title(),
            job.companyName(),
            task.getSourceUrl(),
            job.description(),
            job.location(),
            job.workMode(),
            job.salary(),
            job.salaryCurrency(),
            job.salaryPeriod(),
            job.hardSkills(),
            job.softSkills()
        );
        return new ApplicationInsertDTO(revised, dto.status(), dto.appliedOn(), dto.notes());
    }

    private void checkLimits(long userId) {
        var now = clock.now();
        var cutoff = now.minus(ImportTaskQueue.RETENTION);
        var windowStart = now.minus(QUOTA_WINDOW);
        if (tasks.countCreatedSince(userId, windowStart) >= hourlyLimit) {
            var oldest = tasks.oldestCreatedSince(userId, windowStart).orElse(now);
            var wait = Math.max(1, Duration.between(now, oldest.plus(QUOTA_WINDOW)).toSeconds());
            reject(ImportLimitException.hourlyQuota(wait));
        }
        if (tasks.countActiveByUser(userId, cutoff) >= activeLimit) {
            reject(ImportLimitException.activeLimit());
        }
        if (tasks.countActive(cutoff) >= globalActiveLimit) {
            reject(ImportLimitException.overloaded());
        }
    }

    private static void reject(ImportLimitException limit) {
        log.info("importacao recusada codigo={}", limit.getCode());
        throw limit;
    }

    private Instant cutoff() {
        return clock.now().minus(ImportTaskQueue.RETENTION);
    }

    private static ResourceNotFoundException notFound() {
        return new ResourceNotFoundException("Tarefa de importação não encontrada.");
    }

    public record Confirmation(ApplicationDTO application, boolean created) {}
}
