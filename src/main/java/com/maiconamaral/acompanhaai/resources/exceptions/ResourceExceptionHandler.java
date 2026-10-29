package com.maiconamaral.acompanhaai.resources.exceptions;

import com.maiconamaral.acompanhaai.services.exceptions.*;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.dao.DataIntegrityViolationException;
import java.util.List;

@RestControllerAdvice
public class ResourceExceptionHandler {
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail dataConflict(DataIntegrityViolationException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "Conflito de dados. Verifique se a vaga já foi cadastrada com esse link.");
    }
    @ExceptionHandler(InvalidDataException.class)
    public ProblemDetail invalidData(InvalidDataException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(422), exception.getMessage());
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ProblemDetail concurrentUpdate(ObjectOptimisticLockingFailureException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "O registro foi alterado. Recarregue antes de salvar.");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ProblemDetail uploadTooLarge(MaxUploadSizeExceededException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.PAYLOAD_TOO_LARGE, "A foto deve ter até 2 MiB.");
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ProblemDetail invalidParameters(HandlerMethodValidationException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(422), "Parâmetros de consulta inválidos.");
    }
    @ExceptionHandler(EmailAlreadyExistsException.class)
    public ProblemDetail duplicateEmail(EmailAlreadyExistsException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ProblemDetail notFound(ResourceNotFoundException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    @ExceptionHandler(AuthenticationException.class)
    public ProblemDetail authentication(AuthenticationException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Credenciais inválidas.");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail validation(MethodArgumentNotValidException exception) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, "Dados inválidos.");
        List<FieldErrorDTO> errors = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldErrorDTO(error.getField(), error.getDefaultMessage())).toList();
        problem.setProperty("errors", errors);
        return problem;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail invalidJson(HttpMessageNotReadableException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Corpo JSON inválido.");
    }

    public record FieldErrorDTO(String field, String message) { }
}
