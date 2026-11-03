package com.maiconamaral.acompanhaai.services;

import java.net.URI;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@ConditionalOnProperty(name = "app.password-reset.mail.enabled", havingValue = "true")
public class PasswordResetMailListener {

    private static final Logger LOG = LoggerFactory.getLogger(PasswordResetMailListener.class);
    private final JavaMailSender sender;
    private final Executor executor;
    private final String resetUrl;

    public PasswordResetMailListener(
        JavaMailSender sender,
        @Qualifier("passwordResetMailExecutor") Executor executor,
        @Value("${app.password-reset.mail.reset-url:http://localhost:4200/reset-password}") String resetUrl
    ) {
        this.sender = sender;
        this.executor = executor;
        URI uri = URI.create(resetUrl);
        boolean localHttp =
            "http".equals(uri.getScheme()) &&
            java.util.Set.of("localhost", "127.0.0.1", "::1", "[::1]").contains(
                uri.getHost() == null ? "" : uri.getHost()
            );
        if (
            (!localHttp && !"https".equals(uri.getScheme())) ||
            uri.getHost() == null ||
            uri.getUserInfo() != null ||
            uri.getFragment() != null ||
            uri.getQuery() != null
        ) {
            throw new IllegalArgumentException(
                "Configure uma URL de recuperação HTTPS ou HTTP local, sem credenciais, query ou fragmento."
            );
        }
        this.resetUrl = uri.toASCIIString();
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void afterCommit(PasswordResetService.IssuedToken issued) {
        try {
            executor.execute(() -> send(issued));
        } catch (RejectedExecutionException exception) {
            LOG.warn("Fila de e-mail de recuperação indisponível; entrega não agendada.");
        }
    }

    private void send(PasswordResetService.IssuedToken issued) {
        var message = new SimpleMailMessage();
        message.setFrom("noreply@acompanhaai.test");
        message.setTo(issued.recipient());
        message.setSubject("Acompanhaai — recuperação de senha");
        // URL configurada, independente do Host HTTP; fragmento não é enviado ao servidor.
        message.setText(
            "Foi solicitada a recuperação de acesso à sua conta.\n\n" +
                resetUrl +
                "#token=" +
                issued.token() +
                "\n\n" +
                "O link é de uso único e expira em " +
                issued.expiresAt() +
                " (UTC).\n" +
                "Se você não solicitou a recuperação, ignore esta mensagem.\n"
        );
        try {
            sender.send(message);
        } catch (MailException exception) {
            // Exceções SMTP podem incluir destinatário e conteúdo: não registrar detalhes.
            LOG.warn("Falha na entrega do e-mail de recuperação; nova solicitação será necessária.");
        }
    }
}
