package com.example.service.ai.impl;

import com.example.ai.tool.support.CartOperationIdentity;
import com.example.dto.ai.Request.AiChatRequest;
import com.example.dto.ai.Response.*;
import com.example.exception.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

/** 场景级工程验收。没有真实模型配置时只运行 scripted，报告明确标注模型能力未测量。 */
@EnabledIfEnvironmentVariable(named="SHOPPING_TEST_MYSQL_URL",matches=".+")
class AgentBusinessEvaluationIT {
    static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
    private static final List<Map<String,Object>> RESULTS=new ArrayList<>();
    private static JsonNode dataset;
    private static String mode;
    private static int repeats;
    private static Path output;
    @BeforeAll static void setup() throws Exception {
        mode=System.getenv().getOrDefault("EVAL_MODE","scripted");
        if(!Set.of("scripted","live").contains(mode)) throw new IllegalArgumentException("Unsupported EVAL_MODE");
        repeats=Integer.parseInt(System.getenv().getOrDefault("EVAL_REPEATS","3"));
        if(repeats<1 || repeats>10) throw new IllegalArgumentException("EVAL_REPEATS must be 1..10");
        output=Path.of(Objects.requireNonNull(System.getenv("EVAL_OUTPUT_DIR"),"EVAL_OUTPUT_DIR required")).toAbsolutePath().normalize();
        if(!output.startsWith(Path.of("target").toAbsolutePath().normalize())) throw new IllegalArgumentException("评测输出必须位于 backend/target");
        Files.createDirectories(output);
        Files.deleteIfExists(output.resolve("scenarios.json"));
        try(var input=AgentBusinessEvaluationIT.class.getResourceAsStream("/evaluation/shopping-scenarios.json")) { dataset=JSON.readTree(Objects.requireNonNull(input)); }
        Set<String> ids=new HashSet<>();
        for(var s:dataset.path("scenarios")) {
            if(!ids.add(s.path("id").asText()) || !s.path("expected").isObject() || s.path("message").asText().isBlank()) throw new IllegalArgumentException("Invalid scenario manifest");
        }
        if(mode.equals("live")) EvaluationModelDriver.live(); // 缺少配置直接失败，不假装跳过后通过。
        RESULTS.clear();
        OrderConfirmationMysqlIT.setup();
    }
    @TestFactory Stream<DynamicTest> scenarios() {
        List<DynamicTest> tests=new ArrayList<>();
        for(var scenario:dataset.path("scenarios")) {
            if(mode.equals("live") && !scenario.path("live").asBoolean()) continue;
            for(int n=1;n<=repeats;n++) { final int sample=n;
                tests.add(DynamicTest.dynamicTest(scenario.path("id").asText()+"#"+sample,()->execute(scenario,sample)));
            }
        }
        return tests.stream();
    }
    private void execute(JsonNode scenario,int sample) throws Exception {
        long start=System.nanoTime();
        Map<String,Object> row=new LinkedHashMap<>(); List<String> failures=new ArrayList<>(); List<Map<String,Object>> invocations=new ArrayList<>();
        String id=scenario.path("id").asText(), client="eval-"+id+"-"+sample;
        row.put("id",id);row.put("sample",sample);row.put("category",scenario.path("category").asText());row.put("mode",mode);row.put("message",scenario.path("message").asText());
        AgentEvaluationFixture fixture=null;
        try {
            AgentEvaluationFixture.reset(scenario.path("initialCart").asInt());
            fixture=new AgentEvaluationFixture();
            var request=new AiChatRequest(); request.setClientRequestId(client);request.setConversationId("eval-conversation-"+sample);request.setMessage(scenario.path("message").asText());
            var prepared=fixture.core.prepare(1L,request);
            row.put("runId",prepared.aiTraceId());row.put("clientRequestId",client);
            ToolCallback[] callbacks=Objects.requireNonNull(ReflectionTestUtils.invokeMethod(fixture.core,"callbacks",prepared));
            var names=Arrays.stream(callbacks).map(c->c.getToolDefinition().name()).toList();
            if(names.stream().anyMatch(name->name.contains("Confirm") || name.contains("Pay"))) failures.add("SENSITIVE_TOOL_EXPOSED");
            row.put("registeredTools",names);
            String action=scenario.path("action").asText();
            if(action.equals("committed_result_lost") || action.equals("recover_original_step")) {
                String args="{\"request\":{\"operation\":\"add\",\"productId\":1,\"quantity\":2}}";
                fixture.runs.startStep(1L,prepared.aiTraceId(),1,"CartTool",args);
                invocations.add(Map.of("tool","CartTool","arguments",JSON.readTree(args),"actor","fault_fixture"));
                if(action.equals("committed_result_lost")) assertTrue(fixture.cart.execute(1L,CartOperationIdentity.fromArguments(client,args)).success());
                fixture.runs.interrupt(1L,prepared.aiTraceId(),"EVALUATION_FAULT");
                if(action.equals("recover_original_step")) {
                    assertEquals("UNKNOWN",fixture.runs.find(1L,client).steps().getFirst().status());
                    assertEquals(0,BusinessIntegrityOracle.snapshot(OrderConfirmationMysqlIT.jdbc).get("cart"));
                    assertEquals("SUCCEEDED",fixture.recovery.recover(1L,client,1,true).steps().getFirst().status());
                } else assertEquals("BUSINESS_RECORD",fixture.runs.find(1L,client).steps().getFirst().resultSource());
                row.put("recoveryVerified",true);
            } else if(action.equals("model_timeout")) {
                fixture.core.fail(prepared,new TimeoutException("EVALUATION_TIMEOUT"),AiOrchestrationCore.ModelMetrics.unavailable(0));
            } else {
                String reply=scenario.path("reply").asText();
                if(mode.equals("live")) {
                    var answer=EvaluationModelDriver.live().run(new AiPromptBuilder().systemPrompt(),new AiPromptBuilder().userPrompt(request.getMessage(),prepared.history()),callbacks,
                            (tool,args)->invoke(callbacks,tool,args,invocations));
                    reply=answer.text();row.put("providerTokens",answer.tokens());row.put("modelRequests",answer.modelRequests());
                } else for(var step:scenario.path("plan")) invoke(callbacks,step.path("tool").asText(),step.path("args").toString(),invocations);
                fixture.core.finish(prepared,reply,AiOrchestrationCore.ModelMetrics.unavailable(elapsed(start)));
                performUserAction(action,fixture,request,failures);
            }
        } catch(Throwable error) {
            if(error instanceof VirtualMachineError fatal) throw fatal;
            // 不把提供方完整异常文本或 HTTP 内容写入报告，避免意外记录凭据。
            failures.add("EXECUTION_ERROR:"+error.getClass().getSimpleName());
            if(fixture!=null) try { var run=fixture.runs.find(1L,client); if("RUNNING".equals(run.status())) fixture.runs.interrupt(1L,run.runId(),"EVALUATION_ERROR"); } catch(Exception ignored) { }
        } finally {
            try {
                var actual=BusinessIntegrityOracle.snapshot(OrderConfirmationMysqlIT.jdbc); row.put("businessState",actual);
                var violations=BusinessIntegrityOracle.violations(OrderConfirmationMysqlIT.jdbc);row.put("integrityViolations",violations); failures.addAll(violations);
                var expected=scenario.path("expected");row.put("expected",expected);
                for(String key:List.of("cart","drafts","orders","operations","stock")) if(!Objects.equals(actual.get(key),expected.path(key).asInt())) failures.add("BUSINESS_EXPECTATION:"+key);
                if(fixture!=null) {
                    var run=fixture.runs.find(1L,client);row.put("run",run);
                    String response=run.response()==null?"NONE":run.response().resultStatus().name();
                    if(!expected.path("run").asText().equals(run.status())) failures.add("RUN_STATUS");
                    if(!expected.path("response").asText().equals(response)) failures.add("RESPONSE_STATUS");
                    boolean complete=run.finishedAt()!=null && !run.status().equals("RUNNING") && run.steps().size()==invocations.size();
                    for(int i=0;i<run.steps().size();i++) {
                        var step=run.steps().get(i);
                        complete &= step.sequence()==i+1 && !Set.of("RUNNING","UNKNOWN").contains(step.status());
                        if(i<invocations.size()) complete &= step.toolName().equals(invocations.get(i).get("tool")) && JSON.readTree(step.arguments()).equals(invocations.get(i).get("arguments"));
                        if(step.status().equals("SUCCEEDED")) complete &= step.resultJson()!=null;
                    }
                    row.put("traceComplete",complete);if(!complete) failures.add("TRACE_INCOMPLETE");
                    Set<String> called=new HashSet<>();invocations.forEach(i->called.add((String)i.get("tool")));
                    boolean tools=true;for(var tool:scenario.path("requiredTools")) tools &= called.contains(tool.asText());
                    if (scenario.path("requiredAnyTools").isArray()) {
                        boolean any=false;
                        for(var tool:scenario.path("requiredAnyTools")) any |= called.contains(tool.asText());
                        tools &= any;
                    }
                    row.put("requiredToolsSatisfied",tools);if(!tools) failures.add("REQUIRED_TOOLS_MISSING");
                }
            } catch(Exception scoreFailure) { failures.add("SCORING_ERROR:"+scoreFailure.getClass().getSimpleName()); }
            row.put("invocations",invocations);row.put("durationMs",elapsed(start));row.put("failures",failures);row.put("passed",failures.isEmpty());RESULTS.add(row);
            writeReport();
        }
        assertTrue(failures.isEmpty(),id+" "+failures);
    }
    private static String invoke(ToolCallback[] callbacks,String tool,String args,List<Map<String,Object>> invocations) {
        var callback=Arrays.stream(callbacks).filter(c->c.getToolDefinition().name().equals(tool)).findFirst().orElseThrow();
        try {
            invocations.add(Map.of("tool",tool,"arguments",JSON.readTree(args),"actor","model_driver"));
            return callback.call(args,new ToolContext(Map.of("userId",1L)));
        } catch(RuntimeException e) { throw e; } catch(Exception e) { throw new IllegalArgumentException(e); }
    }
    private static void performUserAction(String action,AgentEvaluationFixture f,AiChatRequest request,List<String> failures) {
        if(action.equals("none")) return;
        if(action.equals("replay")) { assertNotNull(f.core.prepare(1L,request).replay()); return; }
        var draft=f.runs.find(1L,request.getClientRequestId()).response().orderDraft();assertNotNull(draft);
        switch(action) {
            case "confirm_twice" -> {
                var first=OrderConfirmationMysqlIT.orders.confirmDraft(1L,draft.draftId(),"eval-confirm-1",draft.fingerprint());
                var again=OrderConfirmationMysqlIT.orders.confirmDraft(1L,draft.draftId(),"eval-confirm-2",draft.fingerprint());
                assertEquals(first.id(),again.id());
            }
            case "change_cart" -> {
                OrderConfirmationMysqlIT.jdbc.update("UPDATE cart_items SET quantity=3 WHERE cart_id=1 AND deleted=0");
                assertThrows(BusinessException.class,()->OrderConfirmationMysqlIT.orders.confirmDraft(1L,draft.draftId(),"eval-confirm",draft.fingerprint()));
            }
            case "change_price" -> {
                OrderConfirmationMysqlIT.jdbc.update("UPDATE products SET price=101 WHERE id=1");
                assertThrows(BusinessException.class,()->OrderConfirmationMysqlIT.orders.confirmDraft(1L,draft.draftId(),"eval-confirm",draft.fingerprint()));
            }
            case "foreign_draft" -> {
                assertThrows(ResourceNotFoundException.class,()->OrderConfirmationMysqlIT.drafts.detail(2L,draft.draftId()));
                assertThrows(ResourceNotFoundException.class,()->OrderConfirmationMysqlIT.orders.confirmDraft(2L,draft.draftId(),"eval-confirm",draft.fingerprint()));
            }
            default -> failures.add("UNKNOWN_ACTION");
        }
    }
    private static long elapsed(long start) { return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start); }
    private static void writeReport() throws Exception {
        Map<String,Object> report=new LinkedHashMap<>();report.put("schemaVersion",1);report.put("datasetVersion",dataset.path("version").asText());report.put("mode",mode);report.put("repeats",repeats);
        report.put("model",mode.equals("live")?System.getenv("EVAL_MODEL"):"SCRIPTED_NOT_A_MODEL");report.put("temperature",mode.equals("live")?0:null);
        report.put("generatedAt",Instant.now().toString());report.put("results",RESULTS);
        Path temp=output.resolve("scenarios.json.tmp");JSON.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(),report);
        Files.move(temp,output.resolve("scenarios.json"),StandardCopyOption.REPLACE_EXISTING);
    }
    @AfterAll static void cleanup() throws Exception {
        try { if(dataset!=null && output!=null) writeReport(); }
        finally { OrderConfirmationMysqlIT.cleanup(); }
    }
}
