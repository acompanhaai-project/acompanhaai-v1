package com.maiconamaral.acompanhaai.config;

import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@ConditionalOnProperty(name = "app.password-reset.mail.enabled", havingValue = "true")
public class PasswordResetMailConfig {

    @Bean
    public JavaMailSender passwordResetMailSender(
        @Value("${app.password-reset.mail.host:127.0.0.1}") String host,
        @Value("${app.password-reset.mail.port:1025}") int port
    ) {
        // Este adaptador é exclusivamente para SMTP de testes local, sem relay externo.
        if (!Set.of("127.0.0.1", "localhost", "::1").contains(host) || port < 1 || port > 65535) {
            throw new IllegalArgumentException("Configure um servidor SMTP local e uma porta válida.");
        }
        var sender = new JavaMailSenderImpl();
        sender.setHost(host);
        sender.setPort(port);
        sender.setDefaultEncoding("UTF-8");
        var properties = sender.getJavaMailProperties();
        properties.setProperty("mail.smtp.connectiontimeout", "2000");
        properties.setProperty("mail.smtp.timeout", "2000");
        properties.setProperty("mail.smtp.writetimeout", "2000");
        properties.setProperty("mail.smtp.auth", "false");
        properties.setProperty("mail.debug", "false");
        return sender;
    }

    @Bean("passwordResetMailExecutor")
    public ThreadPoolTaskExecutor passwordResetMailExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("password-reset-mail-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        return executor;
    }
}
