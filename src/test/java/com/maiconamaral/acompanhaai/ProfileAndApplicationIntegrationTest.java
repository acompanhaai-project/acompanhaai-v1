package com.maiconamaral.acompanhaai;

import com.jayway.jsonpath.JsonPath;
import com.maiconamaral.acompanhaai.repositories.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.*;
import org.springframework.http.MediaType;
import org.springframework.http.MediaType;
import org.springframework.mock.web.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProfileAndApplicationIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private UserRepository users;
    @Autowired private UserPhotoRepository photos;
    @Autowired private ApplicationRepository applications;
    @Autowired private ApplicationStatusHistoryRepository histories;
    @Autowired private JobRepository jobs;

    @BeforeEach
    void cleanDatabase() { users.deleteAll(); }

    @Test
    void photoIsOptionalAndCanBeUploadedReplacedAndRemoved() throws Exception {
        var session = account("profile@example.com");
        mvc.perform(get("/api/users/me").session(session)).andExpect(jsonPath("$.photoUrl").isEmpty());
        mvc.perform(get("/api/users/me/photo").session(session)).andExpect(status().isNotFound());
        upload(session, png(2, 2)).andExpect(status().isNoContent());
        mvc.perform(get("/api/users/me").session(session))
                .andExpect(jsonPath("$.photoUrl").value("/api/users/me/photo"));
        var result = mvc.perform(get("/api/users/me/photo").session(session)).andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(header().string("Cache-Control", "no-store")).andReturn();
        assertThat(result.getResponse().getContentAsByteArray()).isNotEmpty();
        upload(session, png(3, 3)).andExpect(status().isNoContent());
        assertThat(photos.count()).isEqualTo(1);
        mvc.perform(delete("/api/users/me/photo").session(session).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(delete("/api/users/me/photo").session(session).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/users/me").session(session)).andExpect(jsonPath("$.photoUrl").isEmpty());
    }

    @Test
    void invalidOrOversizedImagesDoNotOverwriteExistingPhoto() throws Exception {
        var session = account("photo-validation@example.com");
        upload(session, png(2, 2)).andExpect(status().isNoContent());
        byte[] original = photos.findAll().getFirst().getContent().clone();
        upload(session, "<svg><script>alert(1)</script></svg>".getBytes()).andExpect(status().is(422));
        upload(session, new byte[2 * 1024 * 1024 + 1]).andExpect(status().is(422));
        upload(session, png(2001, 2000)).andExpect(status().is(422));
        assertThat(photos.findAll().getFirst().getContent()).isEqualTo(original);
    }

    @Test
    void photosArePrivateAndRequireCsrf() throws Exception {
        var owner = account("photo-owner@example.com");
        var other = account("photo-other@example.com");
        upload(owner, png(2, 2)).andExpect(status().isNoContent());
        mvc.perform(get("/api/users/me/photo")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/users/me/photo").session(other)).andExpect(status().isNotFound());
        mvc.perform(multipart(HttpMethod.PUT, "/api/users/me/photo").file(photo(png(2, 2))).session(owner))
                .andExpect(status().isForbidden());
    }

    @Test
    void createReadUpdateAndDeleteApplicationMaintainHistory() throws Exception {
        var session = account("candidate@example.com");
        long id = create(session, "APPLIED");
        mvc.perform(get("/api/applications/" + id).session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.job.title").value("Desenvolvedor Java"))
                .andExpect(jsonPath("$.status").value("APPLIED"));
        update(session, id, "SCREENING", 0).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        update(session, id, "INTERVIEW", 1).andExpect(status().isOk());
        mvc.perform(get("/api/applications/" + id + "/history").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].fromStatus").isEmpty())
                .andExpect(jsonPath("$[0].toStatus").value("APPLIED"))
                .andExpect(jsonPath("$[1].fromStatus").value("APPLIED"))
                .andExpect(jsonPath("$[1].toStatus").value("SCREENING"));
        mvc.perform(delete("/api/applications/" + id).session(session).with(csrf())).andExpect(status().isNoContent());
        assertThat(applications.count()).isZero();
        assertThat(histories.count()).isZero();
        assertThat(jobs.count()).isZero();
        mvc.perform(get("/api/applications/" + id).session(session)).andExpect(status().isNotFound());
    }

    @Test
    void statusFilterPaginationAndOwnershipAreEnforced() throws Exception {
        var owner = account("owner@example.com");
        var other = account("other@example.com");
        long id = create(owner, "SCREENING");
        create(owner, "OFFER");
        create(other, "SCREENING");
        mvc.perform(get("/api/applications?status=SCREENING&page=0&size=1").session(owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(id));
        mvc.perform(get("/api/applications").session(owner)).andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(get("/api/applications/" + id).session(other)).andExpect(status().isNotFound());
        mvc.perform(get("/api/applications/" + id + "/history").session(other)).andExpect(status().isNotFound());
        update(other, id, "OFFER", 0).andExpect(status().isNotFound());
        mvc.perform(delete("/api/applications/" + id).session(other).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(get("/api/applications")).andExpect(status().isUnauthorized());
    }

    @Test
    void staleUpdateIsRejectedWithoutChangingJobOrHistory() throws Exception {
        var session = account("version@example.com");
        long id = create(session, "APPLIED");
        update(session, id, "OFFER", 0).andExpect(status().isOk());
        update(session, id, "REJECTED", 0).andExpect(status().isConflict());
        mvc.perform(get("/api/applications/" + id).session(session)).andExpect(jsonPath("$.status").value("OFFER"));
        assertThat(histories.count()).isEqualTo(2);
        update(session, id, "OFFER", 1).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2));
        assertThat(histories.count()).isEqualTo(2);
    }

    @Test
    void validatesSavedDateUnknownStatusAndPagination() throws Exception {
        var session = account("validation@example.com");
        mvc.perform(post("/api/applications").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"job":{"title":"Java"},"status":"SAVED","appliedOn":"2020-01-01"}
                                """))
                .andExpect(status().is(422));
        mvc.perform(post("/api/applications").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"job\":{\"title\":\"Java\"},\"status\":\"UNKNOWN\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/applications").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"job\":{\"title\":\" \"}}"))
                .andExpect(status().is(422));
        mvc.perform(get("/api/applications?size=101").session(session)).andExpect(status().is(422));
        mvc.perform(get("/api/applications?page=-1").session(session)).andExpect(status().is(422));
        assertThat(applications.count()).isZero();
    }

    @Test
    void supportsSavedAndOutcomeStatusesWithoutInventingDates() throws Exception {
        var session = account("outcomes@example.com");
        long id = create(session, "SAVED");
        update(session, id, "HIRED", 0).andExpect(status().isOk()).andExpect(jsonPath("$.appliedOn").isEmpty());
        update(session, id, "WITHDRAWN", 1).andExpect(status().isOk());
        update(session, id, "REJECTED", 2).andExpect(status().isOk());
        update(session, id, "SAVED", 3).andExpect(status().isOk());
        assertThat(histories.count()).isEqualTo(5);
    }

    @Test
    void deletingAccountCascadesPhotoJobsApplicationsAndHistory() throws Exception {
        var session = account("delete-all@example.com");
        upload(session, png(2, 2)).andExpect(status().isNoContent());
        create(session, "APPLIED");
        mvc.perform(delete("/api/users/me").session(session).with(csrf())).andExpect(status().isNoContent());
        assertThat(photos.count()).isZero();
        assertThat(jobs.count()).isZero();
        assertThat(applications.count()).isZero();
        assertThat(histories.count()).isZero();
    }

    @Test
    void duplicateUrlRollsBackRegistrationAndRemainsPrivatePerUser() throws Exception {
        var owner = account("url-owner@example.com");
        var other = account("url-other@example.com");
        String body = "{\"job\":{\"title\":\"Java\",\"sourceUrl\":\"https://example.com/jobs/1\"}}";
        mvc.perform(post("/api/applications").session(owner).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated());
        mvc.perform(post("/api/applications").session(owner).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isConflict());
        mvc.perform(post("/api/applications").session(other).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated());
        assertThat(applications.count()).isEqualTo(2);
        assertThat(jobs.count()).isEqualTo(2);
        assertThat(histories.count()).isEqualTo(2);
    }

    private MockHttpSession account(String email) throws Exception {
        mvc.perform(post("/api/users").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Teste\",\"email\":\"" + email + "\",\"password\":\"SenhaTeste123!\"}"))
                .andExpect(status().isCreated());
        var result = mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"SenhaTeste123!\"}"))
                .andExpect(status().isOk()).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private long create(MockHttpSession session, String status) throws Exception {
        var result = mvc.perform(post("/api/applications").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"job\":{\"title\":\"Desenvolvedor Java\",\"companyName\":\"Empresa\"},\"status\":\"" + status + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
    }

    private ResultActions update(MockHttpSession session, long id, String newStatus, long version) throws Exception {
        return mvc.perform(put("/api/applications/" + id).session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"job\":{\"title\":\"Desenvolvedor Java\"},\"status\":\"" + newStatus + "\",\"version\":" + version + "}"));
    }

    private ResultActions upload(MockHttpSession session, byte[] bytes) throws Exception {
        return mvc.perform(multipart(HttpMethod.PUT, "/api/users/me/photo").file(photo(bytes)).session(session).with(csrf()));
    }
    private MockMultipartFile photo(byte[] bytes) {
        return new MockMultipartFile("file", "avatar.png", "image/png", bytes);
    }
    private byte[] png(int width, int height) throws Exception {
        var output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", output);
        return output.toByteArray();
    }
}
