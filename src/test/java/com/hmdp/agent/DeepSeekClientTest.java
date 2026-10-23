package com.hmdp.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class DeepSeekClientTest {
    private final ObjectMapper mapper=new ObjectMapper();
    private AgentProperties properties(){AgentProperties p=new AgentProperties();p.setApiKey("test-only-placeholder");return p;}
    private MockRestServiceServer server(DeepSeekClient client){return MockRestServiceServer.bindTo((RestTemplate)ReflectionTestUtils.getField(client,"http")).build();}

    @Test void sendsBearerAndToolProtocolAndReadsRealResponseShape() throws Exception {
        DeepSeekClient client=new DeepSeekClient(properties(),mapper);MockRestServiceServer server=server(client);
        server.expect(requestTo("https://api.deepseek.com/chat/completions"))
            .andExpect(method(HttpMethod.POST)).andExpect(header("Authorization","Bearer test-only-placeholder"))
            .andExpect(jsonPath("$.tool_choice").value("auto"))
            .andExpect(jsonPath("$.thinking.type").value("disabled"))
            .andRespond(withSuccess("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"您好，我可以帮您查商户和订单\"}}]}",MediaType.APPLICATION_JSON));
        assertTrue(client.complete(Collections.emptyList(),mapper.createArrayNode(),true).path("content").asText().contains("您好"));server.verify();
    }
    @Test void noKeyFailsBeforeAnyNetworkCall(){
        DeepSeekClient client=new DeepSeekClient(new AgentProperties(),mapper);
        assertThrows(AgentUnavailableException.class,()->client.complete(Collections.emptyList(),mapper.createArrayNode(),true));
    }
    @Test void upstreamErrorsAreSpecificAndNeverExposeResponseBody(){
        for(HttpStatus status:new HttpStatus[]{HttpStatus.UNAUTHORIZED,HttpStatus.PAYMENT_REQUIRED,HttpStatus.TOO_MANY_REQUESTS}){
            DeepSeekClient client=new DeepSeekClient(properties(),mapper);MockRestServiceServer server=server(client);
            server.expect(requestTo("https://api.deepseek.com/chat/completions"))
                .andRespond(withStatus(status).body("sensitive-upstream-body"));
            AgentUnavailableException error=assertThrows(AgentUnavailableException.class,()->client.complete(Collections.emptyList(),mapper.createArrayNode(),true));
            assertFalse(error.getMessage().contains("sensitive-upstream-body"));server.verify();
        }
    }
}
