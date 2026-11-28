package com.example.service.ai.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class EvaluationModelDriverTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final ToolCallback TOOL=new ToolCallback() {
        public ToolDefinition getToolDefinition() { return ToolDefinition.builder().name("CartTool").description("test").inputSchema("{\"type\":\"object\"}").build(); }
        public String call(String args) { throw new UnsupportedOperationException(); }
    };
    @Test void executesProviderSelectedToolAndFeedsBackItsActualResult() throws Exception {
        AtomicInteger requests=new AtomicInteger(),tools=new AtomicInteger();
        var driver=new EvaluationModelDriver("test",body->{
            if(requests.incrementAndGet()==1) return JSON.readTree("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"tool_calls\":[{\"id\":\"call-1\",\"type\":\"function\",\"function\":{\"name\":\"CartTool\",\"arguments\":\"{}\"}}]}}],\"usage\":{\"total_tokens\":10}}");
            assertTrue(JSON.writeValueAsString(body.get("messages")).contains("ACTUAL_RESULT"));
            return JSON.readTree("{\"choices\":[{\"message\":{\"content\":\"完成\"}}],\"usage\":{\"total_tokens\":5}}");
        });
        var answer=driver.run("system","user",new ToolCallback[]{TOOL},(tool,args)->{tools.incrementAndGet();return "ACTUAL_RESULT";});
        assertEquals(1,tools.get());assertEquals(15,answer.tokens());assertEquals(2,answer.modelRequests());
    }
    @Test void unregisteredPaymentToolNeverExecutes() {
        var driver=new EvaluationModelDriver("test",body->JSON.readTree("{\"choices\":[{\"message\":{\"tool_calls\":[{\"id\":\"x\",\"function\":{\"name\":\"PayTool\",\"arguments\":\"{}\"}}]}}]}"));
        assertThrows(IllegalStateException.class,()->driver.run("s","u",new ToolCallback[]{TOOL},(tool,args)->{fail("禁止执行未注册工具");return "";}));
    }
    @Test void malformedResponseFailsInsteadOfProducingEmptySuccess() {
        var driver=new EvaluationModelDriver("test",body->JSON.readTree("{}"));
        assertThrows(IllegalStateException.class,()->driver.run("s","u",new ToolCallback[]{TOOL},(tool,args)->""));
    }
    @Test void callBudgetStopsUnboundedProviderLoopAndMissingUsageStaysUnknown() throws Exception {
        AtomicInteger calls=new AtomicInteger();
        var driver=new EvaluationModelDriver("test",body->JSON.readTree("{\"choices\":[{\"message\":{\"tool_calls\":[{\"id\":\"x\",\"function\":{\"name\":\"CartTool\",\"arguments\":\"{}\"}}]}}]}"));
        assertThrows(IllegalStateException.class,()->driver.run("s","u",new ToolCallback[]{TOOL},(tool,args)->{calls.incrementAndGet();return "{}";}));assertEquals(7,calls.get());
        var noUsage=new EvaluationModelDriver("test",body->JSON.readTree("{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}"));
        assertNull(noUsage.run("s","u",new ToolCallback[]{TOOL},(t,a)->"").tokens());
    }
}
