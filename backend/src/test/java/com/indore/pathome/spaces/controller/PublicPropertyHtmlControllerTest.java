package com.indore.pathome.spaces.controller;

import com.indore.pathome.spaces.config.SecurityConfig;
import com.indore.pathome.spaces.security.JwtAuthenticationFilter;
import com.indore.pathome.spaces.security.JwtUtils;
import com.indore.pathome.spaces.service.PublicPropertyHtmlService;
import com.indore.pathome.spaces.service.PublicPropertyReadService;
import com.indore.pathome.spaces.security.OAuth2AuthenticationSuccessHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = PublicPropertyHtmlController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, PublicPropertyHtmlService.class})
@TestPropertySource(properties = "pathome.crm.public-origin=https://pathome.test")
class PublicPropertyHtmlControllerTest {
    @Autowired private MockMvc mockMvc;
    @MockBean private PublicPropertyReadService publicProperties;
    @MockBean private OAuth2AuthenticationSuccessHandler oAuth2SuccessHandler;
    @MockBean private JwtUtils jwtUtils;

    @BeforeEach
    void setUp() {
        when(publicProperties.findPublicSnapshot(201L)).thenReturn(Optional.of(
                new PublicPropertyReadService.PublicPropertySnapshot(
                        201L,
                        "2 BHK Flat in Arera Colony, Bhopal",
                        "Bhopal",
                        "Arera Colony",
                        "2 BHK",
                        850.0,
                        new BigDecimal("21000.01"),
                        "https://res.cloudinary.com/pathome/image/upload/cover.webp"
                )));
    }

    @Test
    void getReturnsPropertySpecificRawHeadMetadataInOnePackagedShell() throws Exception {
        mockMvc.perform(get("/property/201")
                        .header("Host", "attacker.example")
                        .queryParam("origin", "https://evil.example"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Content-Type", containsString("text/html")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().string(containsString(
                        "<title>2 BHK Flat in Arera Colony, Bhopal | Pathome</title>")))
                .andExpect(content().string(containsString("Rent ₹21,000.01 per month")))
                .andExpect(content().string(containsString("property=\"og:url\" content=\"https://pathome.test/property/201\"")))
                .andExpect(content().string(containsString("property=\"og:image\"")))
                .andExpect(content().string(containsString("rel=\"canonical\" href=\"https://pathome.test/property/201\"")))
                .andExpect(content().string(containsString("id=\"root\"")))
                .andExpect(content().string(containsString("/assets/index-build123.js")))
                .andExpect(content().string(not(containsString("data-pathome-shell-title"))))
                .andExpect(content().string(not(containsString("attacker.example"))));
    }

    @Test
    void headMatchesStatusAndHeadersWithoutABody() throws Exception {
        mockMvc.perform(head("/property/201"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Content-Type", containsString("text/html")))
                .andExpect(header().exists("Content-Length"))
                .andExpect(content().string(""));
    }

    @Test
    @WithMockUser(roles = "TENANT")
    void authenticatedRequestSeesTheSamePublicFacts() throws Exception {
        mockMvc.perform(get("/property/201"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(
                        "property=\"og:url\" content=\"https://pathome.test/property/201\"")))
                .andExpect(content().string(not(containsString("Controlled customer"))));
    }

    @Test
    void missingPropertyReturnsNeutralNoindexHtml() throws Exception {
        when(publicProperties.findPublicSnapshot(999L)).thenReturn(Optional.empty());
        mockMvc.perform(get("/property/999"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().string(containsString("noindex,nofollow")))
                .andExpect(content().string(containsString("This property is no longer available.")))
                .andExpect(content().string(not(containsString("og:title"))));
    }

    @Test
    void backingFailureReturnsNeutral503WithoutInternalDetails() throws Exception {
        when(publicProperties.findPublicSnapshot(202L))
                .thenThrow(new IllegalStateException("database password sentinel"));
        mockMvc.perform(get("/property/202"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().string(containsString("noindex,nofollow")))
                .andExpect(content().string(not(containsString("database password sentinel"))))
                .andExpect(content().string(not(containsString("og:title"))));
    }

    @Test
    void invalidPositiveLongGrammarReturnsNeutral404() throws Exception {
        for (String invalid : new String[]{"0", "-1", "01", "9223372036854775808", "abc"}) {
            mockMvc.perform(get("/property/" + invalid))
                    .andExpect(status().isNotFound())
                    .andExpect(content().string(containsString("noindex,nofollow")));
        }
    }

    @Test
    void alternateAndMutatingRoutesDoNotOpenThePublicMatcher() throws Exception {
        mockMvc.perform(get("/property/201/extra"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/property/201"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void htmlEscapesTextAndAttributePayloads() throws Exception {
        when(publicProperties.findPublicSnapshot(203L)).thenReturn(Optional.of(
                new PublicPropertyReadService.PublicPropertySnapshot(
                        203L,
                        "2 BHK \"Flat\" <script>alert('x')</script> & home",
                        "Bhopal & \"quoted\"",
                        "Area <b>west</b>",
                        null,
                        null,
                        null,
                        "https://res.cloudinary.com/pathome/image/upload/a&b.webp"
                )));

        mockMvc.perform(get("/property/203"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("<script>"))))
                .andExpect(content().string(not(containsString("content=\"Bhopal &"))))
                .andExpect(content().string(containsString("&lt;script&gt;")))
                .andExpect(content().string(containsString("&amp;")))
                .andExpect(content().string(containsString("&quot;")));
    }

    @Test
    void getAndHeadHaveEquivalentRepresentationForLiteralReplacementCharacters() throws Exception {
        when(publicProperties.findPublicSnapshot(205L)).thenReturn(Optional.of(
                new PublicPropertyReadService.PublicPropertySnapshot(
                        205L, "2 BHK $1 \\ home", "Bhopal $0",
                        "Arera ${name}\\West", "2 BHK", 850.0,
                        new BigDecimal("21000.01"), null)));

        MvcResult getResult = mockMvc.perform(get("/property/205")
                        .header("Host", "attacker.example")
                        .header("Forwarded", "host=forwarded.example;proto=http")
                        .header("X-Forwarded-Host", "proxy.example")
                        .queryParam("origin", "https://evil.example/$1")
                        .cookie(new jakarta.servlet.http.Cookie("origin", "https://evil.example")))
                .andExpect(status().isOk())
                .andReturn();
        MvcResult headResult = mockMvc.perform(head("/property/205")
                        .header("Host", "attacker.example")
                        .header("Forwarded", "host=forwarded.example;proto=http")
                        .header("X-Forwarded-Host", "proxy.example")
                        .queryParam("origin", "https://evil.example/$1")
                        .cookie(new jakarta.servlet.http.Cookie("origin", "https://evil.example")))
                .andExpect(status().isOk())
                .andReturn();

        assertGetHeadParity(getResult, headResult);
        String body = getResult.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(body.contains("2 BHK $1 \\ home"));
        assertTrue(body.contains("Arera ${name}\\West"));
        assertTrue(body.contains("content=\"https://pathome.test/property/205\""));
        assertFalse(body.contains("attacker.example"));
        assertFalse(body.contains("forwarded.example"));
        assertFalse(body.contains("proxy.example"));
        assertFalse(body.contains("evil.example"));
    }

    @Test
    void notFoundGetHeadParityHoldsForMissingAndMalformedIds() throws Exception {
        when(publicProperties.findPublicSnapshot(999L)).thenReturn(Optional.empty());

        MvcResult missingGet = mockMvc.perform(get("/property/999"))
                .andExpect(status().isNotFound())
                .andReturn();
        MvcResult missingHead = mockMvc.perform(head("/property/999"))
                .andExpect(status().isNotFound())
                .andReturn();
        assertGetHeadParity(missingGet, missingHead);
        assertTrue(missingGet.getResponse().getContentAsString(StandardCharsets.UTF_8)
                .contains("noindex,nofollow"));

        MvcResult malformedHead = mockMvc.perform(head("/property/01"))
                .andExpect(status().isNotFound())
                .andReturn();
        MvcResult malformedGet = mockMvc.perform(get("/property/01"))
                .andExpect(status().isNotFound())
                .andReturn();
        assertGetHeadParity(malformedGet, malformedHead);
    }

    @Test
    void serviceFailureGetHeadParityIsNeutralAndRecovers() throws Exception {
        when(publicProperties.findPublicSnapshot(202L))
                .thenThrow(new IllegalStateException("private-parity-sentinel"));

        MvcResult failedGet = mockMvc.perform(get("/property/202"))
                .andExpect(status().isServiceUnavailable())
                .andReturn();
        MvcResult failedHead = mockMvc.perform(head("/property/202"))
                .andExpect(status().isServiceUnavailable())
                .andReturn();
        assertGetHeadParity(failedGet, failedHead);
        String failureBody = failedGet.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(failureBody.contains("noindex,nofollow"));
        assertFalse(failureBody.contains("private-parity-sentinel"));
        assertFalse(failureBody.contains("og:title"));

        MvcResult recoveredHead = mockMvc.perform(head("/property/201"))
                .andExpect(status().isOk())
                .andReturn();
        MvcResult recoveredGet = mockMvc.perform(get("/property/201"))
                .andExpect(status().isOk())
                .andReturn();
        assertGetHeadParity(recoveredGet, recoveredHead);
        assertTrue(recoveredGet.getResponse().getContentAsString(StandardCharsets.UTF_8)
                .contains("https://pathome.test/property/201"));
    }

    @Test
    void omittedOptionalFactsDoNotCreatePlaceholders() throws Exception {
        when(publicProperties.findPublicSnapshot(204L)).thenReturn(Optional.of(
                new PublicPropertyReadService.PublicPropertySnapshot(
                        204L, "Plot in Nipania, Indore", "Indore", "Nipania",
                        null, null, null, null)));

        mockMvc.perform(get("/property/204"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("og:image"))))
                .andExpect(content().string(not(containsString("NaN"))))
                .andExpect(content().string(not(containsString("null"))));
    }

    @Test
    void twentyIndependentPropertyUrlsProduceOnlyTheirOwnMetadata() throws Exception {
        for (long id = 301L; id <= 320L; id++) {
            long expectedId = id;
            when(publicProperties.findPublicSnapshot(id)).thenAnswer(invocation ->
                    Optional.of(new PublicPropertyReadService.PublicPropertySnapshot(
                            expectedId, "Property " + expectedId, "Indore",
                            "Locality " + expectedId, "2 BHK", 850.0,
                            new BigDecimal("21000.01"), null)));
            mockMvc.perform(get("/property/" + id))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString(
                            "property=\"og:url\" content=\"https://pathome.test/property/" + id + "\"")))
                    .andExpect(content().string(containsString(
                            "property=\"og:title\" content=\"Property " + id + "\"")));
        }
    }

    private static void assertGetHeadParity(MvcResult getResult, MvcResult headResult)
            throws Exception {
        assertEquals(getResult.getResponse().getStatus(), headResult.getResponse().getStatus());
        for (String header : new String[]{
                "Content-Type", "Content-Length", "Cache-Control",
                "X-Content-Type-Options", "Referrer-Policy"
        }) {
            assertEquals(getResult.getResponse().getHeader(header),
                    headResult.getResponse().getHeader(header), header);
        }
        String getBody = getResult.getResponse().getContentAsString(StandardCharsets.UTF_8);
        String headBody = headResult.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertFalse(getBody.isEmpty());
        assertTrue(headBody.isEmpty());
        assertEquals(getBody.getBytes(StandardCharsets.UTF_8).length,
                Integer.parseInt(getResult.getResponse().getHeader("Content-Length")));
    }
}
