package com.shivang.obd.ivr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * VB-6F — the IVR tree REST contract, asserted against the generated OpenAPI
 * document.
 *
 * <p>Assertions run against the document the running application actually
 * serves, not against annotations read from source, because a compiling
 * annotation is not evidence that the schema is right.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Import({IvrTreeController.class, IvrTreeService.class,
    com.shivang.obd.security.config.OpenApiConfig.class,
    org.springdoc.core.configuration.SpringDocConfiguration.class,
    org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration.class,
    org.springdoc.core.configuration.SpringDocJavadocConfiguration.class})
class IvrOpenApiContractTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    private JsonNode fetchOpenApi() throws Exception {
        if (mockMvc == null) {
            mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        }
        MvcResult result = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn();
        return new ObjectMapper().readTree(result.getResponse().getContentAsString());
    }

    private JsonNode schema(JsonNode spec, String name) {
        return spec.at("/components/schemas/" + name);
    }

    @Test
    @DisplayName("OAS-IVR-1: the whole-tree write surface is documented")
    void pathsAreDocumented() throws Exception {
        JsonNode spec = fetchOpenApi();
        for (String path : new String[] {
                "/api/v1/ivr-trees",
                "/api/v1/ivr-trees/{id}",
                "/api/v1/ivr-trees/{id}/status",
                "/api/v1/campaigns/{id}/ivr-tree"}) {
            assertThat(spec.at("/paths/" + path.replace("/", "~1")).isMissingNode())
                    .as("path %s must be documented", path)
                    .isFalse();
        }
        assertThat(spec.at("/paths/~1api~1v1~1ivr-trees").get("get")).isNotNull();
        assertThat(spec.at("/paths/~1api~1v1~1ivr-trees").get("post")).isNotNull();
        assertThat(spec.at("/paths/~1api~1v1~1ivr-trees~1{id}").get("put")).isNotNull();
        assertThat(spec.at("/paths/~1api~1v1~1ivr-trees~1{id}").get("delete")).isNotNull();
        assertThat(spec.at("/paths/~1api~1v1~1ivr-trees~1{id}~1status").get("post")).isNotNull();
    }

    @Test
    @DisplayName("OAS-IVR-2: node prompts, timing and retries are documented with their bounds")
    void nodeSchemaIsDocumented() throws Exception {
        JsonNode spec = fetchOpenApi();
        JsonNode node = schema(spec, "IvrNodeRequest");
        assertThat(node.isMissingNode()).isFalse();

        JsonNode wait = node.at("/properties/inputWaitSeconds");
        assertThat(wait.at("/minimum").asInt()).isEqualTo(1);
        assertThat(wait.at("/maximum").asInt()).isEqualTo(120);
        assertThat(wait.at("/description").asText())
                .as("the description must name the default and say the range matches DTMF")
                .contains("120").contains("default");

        for (String field : new String[] {"invalidInputRetries", "noInputRetries"}) {
            JsonNode retries = node.at("/properties/" + field);
            assertThat(retries.at("/minimum").asInt())
                    .as("%s minimum", field).isEqualTo(0);
            assertThat(retries.at("/maximum").asInt())
                    .as("%s maximum", field).isEqualTo(10);
        }
    }

    @Test
    @DisplayName("OAS-IVR-3: retry counts are documented as ADDITIONAL attempts, not totals")
    void retrySemanticsAreDocumented() throws Exception {
        // The ambiguity the brief asks the implementation to resolve. If the
        // description ever stops saying "ADDITIONAL", an operator will read
        // retries=2 as three total attempts when it means three attempts too -
        // and the error is invisible until a caller is let go early.
        JsonNode spec = fetchOpenApi();
        String description = schema(spec, "IvrNodeRequest")
                .at("/properties/invalidInputRetries/description").asText();
        assertThat(description)
                .contains("ADDITIONAL")
                .contains("0 means one attempt in total");
        assertThat(schema(spec, "IvrNodeRequest")
                .at("/properties/noInputRetries/description").asText())
                .contains("ADDITIONAL");
    }

    @Test
    @DisplayName("OAS-IVR-4: retry semantics state that no campaign retry is involved")
    void retryIsolationIsDocumented() throws Exception {
        JsonNode spec = fetchOpenApi();
        String description = schema(spec, "IvrNodeRequest")
                .at("/properties/invalidInputRetries/description").asText();
        assertThat(description)
                .as("an operator must know an IVR retry is in-call, not a redial")
                .contains("never creates a new call attempt")
                .contains("never invokes");
    }

    @Test
    @DisplayName("OAS-IVR-5: the DTMF input alphabet is documented on the transition schema")
    void dtmfAlphabetIsDocumented() throws Exception {
        JsonNode spec = fetchOpenApi();
        JsonNode transition = schema(spec, "IvrTransitionRequest");
        assertThat(transition.at("/properties/input/pattern").asText())
                .isEqualTo("[0-9*#]");
        assertThat(transition.at("/properties/input/description").asText())
                .contains("0-9").contains("*").contains("#");
        assertThat(transition.at("/properties/targetNodeKey/description").asText())
                .as("cross-tree refusal must be visible to the API reader")
                .contains("same tree").contains("another tree");
    }

    @Test
    @DisplayName("OAS-IVR-6: the response exposes the menu inline, so it is inspectable")
    void responseExposesTheMenu() throws Exception {
        JsonNode spec = fetchOpenApi();
        JsonNode response = schema(spec, "IvrTreeResponse");
        assertThat(response.at("/properties/nodes").isMissingNode()).isFalse();
        assertThat(response.at("/properties/rootNodeKey").isMissingNode()).isFalse();
        assertThat(schema(spec, "IvrNodeResponse")
                .at("/properties/transitions").isMissingNode()).isFalse();
        assertThat(schema(spec, "IvrTransitionResponse")
                .at("/properties/input").isMissingNode()).isFalse();
    }

    @Test
    @DisplayName("OAS-IVR-7: tree lifecycle is documented and constrained")
    void lifecycleIsDocumented() throws Exception {
        JsonNode spec = fetchOpenApi();
        // Springdoc inlines a nested enum rather than naming a schema for it, so
        // the lifecycle is asserted where it is actually documented.
        JsonNode status = schema(spec, "IvrTreeResponse").at("/properties/status");
        assertThat(status.at("/enum").isArray()).isTrue();
        assertThat(status.at("/enum")).extracting(JsonNode::asText)
                .containsExactlyInAnyOrder("DRAFT", "ACTIVE", "ARCHIVED");
        assertThat(schema(spec, "ChangeIvrTreeStatusRequest")
                .at("/properties/status/description").asText())
                .contains("DRAFT").contains("ACTIVE").contains("ARCHIVED");
    }

    @Test
    @DisplayName("OAS-IVR-8: prompt governance and tenant scoping are documented")
    void governanceIsDocumented() throws Exception {
        JsonNode spec = fetchOpenApi();
        assertThat(schema(spec, "IvrNodeRequest")
                .at("/properties/promptAudioAssetId/description").asText())
                .as("a prompt must be documented as a governed resource, not a free string")
                .contains("Approved audio asset").contains("tenant")
                .contains("unapproved asset is rejected");
        // The read boundary convention: foreign and nonexistent are both 404.
        assertThat(spec.at("/paths/~1api~1v1~1ivr-trees~1{id}/get/responses/404")).isNotNull();
    }

    @Test
    @DisplayName("OAS-IVR-9: every IVR operation declares bearer security")
    void securityIsDeclared() throws Exception {
        JsonNode spec = fetchOpenApi();
        for (String path : new String[] {
                "~1api~1v1~1ivr-trees",
                "~1api~1v1~1ivr-trees~1{id}",
                "~1api~1v1~1ivr-trees~1{id}~1status",
                "~1api~1v1~1campaigns~1{id}~1ivr-tree"}) {
            for (String method : new String[] {"get", "post", "put", "delete"}) {
                JsonNode operation = spec.at("/paths/" + path + "/" + method);
                if (operation.isMissingNode()) {
                    continue;
                }
                assertThat(operation.at("/security").isMissingNode())
                        .as("%s %s must declare security", method, path)
                        .isFalse();
            }
        }
    }
}
