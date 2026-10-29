package com.maiconamaral.acompanhaai;

import com.maiconamaral.acompanhaai.entities.*;
import com.maiconamaral.acompanhaai.entities.enums.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DatabaseMappingIntegrationTest {
    @Autowired private EntityManager em;

    private User user(String email) {
        User user = new User("Teste", email, "hash-only-for-persistence-test");
        em.persist(user);
        return user;
    }

    private Job job(User user) {
        Job job = new Job(user, "Java Developer");
        em.persist(job);
        return job;
    }

    @Test
    void persistsRelationshipsEnumsMoneyAndJsonAndUpdatesVersion() {
        Job job = job(user("mapping@example.com"));
        job.setSalaryMin(new BigDecimal("4000.00"));
        job.setSalaryMax(new BigDecimal("6000.00"));
        job.setSalaryCurrency("BRL");
        job.setSalaryPeriod(SalaryPeriod.MONTH);
        JobSkill skill = new JobSkill(job, "Java", "java", SkillType.HARD);
        em.persist(skill);
        Application application = new Application(job);
        em.persist(application);
        ApplicationStatusHistory history = new ApplicationStatusHistory(
                application, null, ApplicationStatus.SAVED, "Registro inicial");
        em.persist(history);
        ImportTask task = new ImportTask(job.getUser(), ImportInputType.TEXT, "Vaga Java");
        task.setExtractedData(Map.of("title", "Java Developer", "skills", java.util.List.of("Java")));
        em.persist(task);
        em.flush();
        Long jobId = job.getId();
        Long taskId = task.getId();
        Long applicationId = application.getId();
        Long skillId = skill.getId();
        Long historyId = history.getId();
        long originalVersion = job.getVersion();
        em.clear();

        Job loaded = em.find(Job.class, jobId);
        assertThat(loaded.getSalaryMin()).isEqualByComparingTo("4000.00");
        assertThat(loaded.getSalaryPeriod()).isEqualTo(SalaryPeriod.MONTH);
        assertThat(loaded.getCreatedAt()).isNotNull();
        assertThat(em.find(ImportTask.class, taskId).getExtractedData())
                .containsEntry("title", "Java Developer");
        assertThat(em.find(Application.class, applicationId).getJob().getId()).isEqualTo(jobId);
        assertThat(em.find(JobSkill.class, skillId).getType()).isEqualTo(SkillType.HARD);
        assertThat(em.find(ApplicationStatusHistory.class, historyId).getToStatus())
                .isEqualTo(ApplicationStatus.SAVED);
        loaded.setTitle("Senior Java Developer");
        em.flush();
        assertThat(loaded.getVersion()).isGreaterThan(originalVersion);
        assertThat(loaded.getUpdatedAt()).isAfterOrEqualTo(loaded.getCreatedAt());
    }

    @Test
    void rejectsImportLinkedToAnotherUsersJob() {
        Job job = job(user("owner@example.com"));
        User other = user("other@example.com");
        ImportTask task = new ImportTask(other, ImportInputType.TEXT, "Descricao");
        task.setJob(job);
        assertThatThrownBy(() -> { em.persist(task); em.flush(); })
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void rejectsApplicationLinkedToAnotherUsersJobEvenThroughDirectSql() {
        Job job = job(user("owner@example.com"));
        User other = user("other@example.com");
        em.flush();
        assertThatThrownBy(() -> em.createNativeQuery("""
                INSERT INTO tb_application (user_id, job_id, status, created_at, updated_at, version)
                VALUES (:user, :job, 'SAVED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
                """).setParameter("user", other.getId()).setParameter("job", job.getId()).executeUpdate())
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void rejectsInvertedSalaryRange() {
        Job job = job(user("salary@example.com"));
        job.setSalaryMin(new BigDecimal("6000.00"));
        job.setSalaryMax(new BigDecimal("4000.00"));
        assertThatThrownBy(em::flush).isInstanceOf(PersistenceException.class);
    }

    @Test
    void rejectsDuplicateUrlForSameUserButAllowsAnotherUser() {
        User owner = user("duplicate@example.com");
        Job first = job(owner);
        setUrl(first);
        Job anotherUserJob = job(user("another@example.com"));
        setUrl(anotherUserJob);
        em.flush();
        Job duplicate = job(owner);
        setUrl(duplicate);
        assertThatThrownBy(em::flush).isInstanceOf(PersistenceException.class);
    }

    @Test
    void deletingAccountCascadesToOwnedData() {
        User owner = user("delete@example.com");
        Job job = job(owner);
        Application application = new Application(job);
        em.persist(application);
        JobSkill skill = new JobSkill(job, "Java", "java", SkillType.HARD);
        em.persist(skill);
        ApplicationStatusHistory history = new ApplicationStatusHistory(
                application, null, ApplicationStatus.SAVED, null);
        em.persist(history);
        ImportTask task = new ImportTask(owner, ImportInputType.TEXT, "Descricao");
        task.setJob(job);
        em.persist(task);
        em.flush();
        Long jobId = job.getId();
        Long applicationId = application.getId();
        Long skillId = skill.getId();
        Long historyId = history.getId();
        Long taskId = task.getId();
        Long ownerId = owner.getId();
        // Simulate account deletion in a fresh request; child removal belongs to the database.
        em.clear();
        em.remove(em.find(User.class, ownerId));
        em.flush();
        em.clear();
        assertThat(em.find(Job.class, jobId)).isNull();
        assertThat(em.find(Application.class, applicationId)).isNull();
        assertThat(em.find(JobSkill.class, skillId)).isNull();
        assertThat(em.find(ApplicationStatusHistory.class, historyId)).isNull();
        assertThat(em.find(ImportTask.class, taskId)).isNull();
    }

    private void setUrl(Job job) {
        job.setSourceUrl("https://example.com/jobs/1");
        job.setNormalizedUrl("https://example.com/jobs/1");
        job.setUrlHash("a".repeat(64));
    }
}
