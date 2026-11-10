package com.maiconamaral.acompanhaai.services;

import com.maiconamaral.acompanhaai.dto.ImportTaskDTO;
import com.maiconamaral.acompanhaai.dto.ImportTaskInputDTO;
import com.maiconamaral.acompanhaai.entities.ImportTask;
import com.maiconamaral.acompanhaai.entities.enums.ImportInputType;
import com.maiconamaral.acompanhaai.entities.enums.ImportStatus;
import com.maiconamaral.acompanhaai.repositories.ImportTaskRepository;
import com.maiconamaral.acompanhaai.repositories.UserRepository;
import com.maiconamaral.acompanhaai.services.exceptions.ImportAlreadyCompletedException;
import com.maiconamaral.acompanhaai.services.exceptions.InvalidDataException;
import com.maiconamaral.acompanhaai.services.exceptions.ResourceNotFoundException;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ImportTaskService {

    private final ImportTaskRepository tasks;
    private final UserRepository users;
    private final JobUrlService urls;
    private final DatabaseClock clock;

    public ImportTaskService(
        ImportTaskRepository tasks,
        UserRepository users,
        JobUrlService urls,
        DatabaseClock clock
    ) {
        this.tasks = tasks;
        this.users = users;
        this.urls = urls;
        this.clock = clock;
    }

    @Transactional
    public ImportTaskDTO insert(Long userId, ImportTaskInputDTO dto) {
        var user = users.getReferenceById(userId);
        var task = tasks.saveAndFlush(new ImportTask(user, dto.inputType(), input(dto)));
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
            throw new ImportAlreadyCompletedException();
        }
        tasks.delete(task);
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

    private Instant cutoff() {
        return clock.now().minus(ImportTaskQueue.RETENTION);
    }

    private static ResourceNotFoundException notFound() {
        return new ResourceNotFoundException("Tarefa de importação não encontrada.");
    }
}
