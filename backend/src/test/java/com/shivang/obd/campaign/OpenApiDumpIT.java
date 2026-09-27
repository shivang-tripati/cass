package com.shivang.obd.campaign;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@org.springframework.boot.test.context.SpringBootTest(webEnvironment =
    org.springframework.boot.test.context.SpringBootTest.WebEnvironment.MOCK)
@Import({CampaignController.class, CampaignService.class, CampaignMapper.class,
    CampaignReadinessService.class, CampaignExecutionService.class,
    CallAttemptService.class, CampaignLifecyclePolicy.class,
    CampaignResourceValidationService.class,
    com.shivang.obd.campaign.event.CampaignEventPublisher.class,
    com.shivang.obd.security.config.OpenApiConfig.class,
    org.springdoc.core.configuration.SpringDocConfiguration.class,
    org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration.class,
    org.springdoc.core.configuration.SpringDocJavadocConfiguration.class})
class OpenApiDumpIT {
    @Autowired
    private WebApplicationContext context;

    @Test
    void dump() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
        MvcResult r = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn();
        String body = r.getResponse().getContentAsString();
        String collectionKey = "\"/api/v1/campaigns\":{";
        String idKey = "\"/api/v1/campaigns/{id}\":{";
        System.out.println("OAPIDUMP collectionKey=" + body.contains(collectionKey)
            + " idKey=" + body.contains(idKey));
        int i = body.indexOf(collectionKey);
        if (i >= 0) {
            int post = body.indexOf("\"post\"", i);
            System.out.println("OAPIDUMP-POST "
                + body.substring(post, Math.min(body.length(), post + 1100)));
        }
        int k = body.indexOf(idKey);
        if (k >= 0) {
            int put = body.indexOf("\"put\"", k);
            System.out.println("OAPIDUMP-PUT "
                + body.substring(put, Math.min(body.length(), put + 900)));
        }
    }
}
