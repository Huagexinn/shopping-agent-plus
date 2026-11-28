package com.example.service.ai.impl;

import com.fasterxml.jackson.databind.*;
import org.springframework.ai.tool.ToolCallback;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.function.BiFunction;

/** 可选模型评测传输层；使用生产工具定义和回调，最多 7 次工具调用，不读取日常商城凭据。 */
final class EvaluationModelDriver {
    private static final ObjectMapper JSON=new ObjectMapper();
    interface Transport { JsonNode send(Map<String,Object> body) throws Exception; }
    record Answer(String text,Long tokens,int modelRequests) {}
    private final String model;
    private final Transport transport;
    EvaluationModelDriver(String model,Transport transport) { this.model=model; this.transport=transport; }
    static EvaluationModelDriver live() {
        String endpoint=required("EVAL_CHAT_COMPLETIONS_URL"), key=required("EVAL_API_KEY"), model=required("EVAL_MODEL");
        URI uri=URI.create(endpoint);
        if(!"https".equals(uri.getScheme()) || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null)
            throw new IllegalArgumentException("真实模型评测必须指定无凭据/查询参数的 HTTPS chat/completions URL");
        var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
        return new EvaluationModelDriver(model,body -> {
            var request=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(40)).header("Authorization","Bearer "+key)
                    .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build();
            var response=client.send(request,HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()!=200) throw new IllegalStateException("MODEL_HTTP_"+response.statusCode());
            return JSON.readTree(response.body());
        });
    }
    Answer run(String system,String user,ToolCallback[] callbacks,BiFunction<String,String,String> invoke) throws Exception {
        Map<String,ToolCallback> allowed=new LinkedHashMap<>();
        List<Object> definitions=new ArrayList<>();
        for(var callback:callbacks) {
            var definition=callback.getToolDefinition(); allowed.put(definition.name(),callback);
            definitions.add(Map.of("type","function","function",Map.of("name",definition.name(),"description",definition.description(),"parameters",JSON.readTree(definition.inputSchema()))));
        }
        List<Object> messages=new ArrayList<>(); messages.add(Map.of("role","system","content",system)); messages.add(Map.of("role","user","content",user));
        long tokens=0; boolean usageComplete=true; int calls=0;
        long deadline=System.nanoTime()+Duration.ofSeconds(120).toNanos();
        for(int round=1;round<=8;round++) {
            if(System.nanoTime()>deadline) throw new IllegalStateException("MODEL_EVALUATION_DEADLINE");
            JsonNode response=transport.send(Map.of("model",model,"temperature",0,"messages",messages,"tools",definitions,"tool_choice","auto","stream",false));
            var usage=response.path("usage").path("total_tokens"); if(usage.isIntegralNumber() && usage.asLong()>=0) tokens+=usage.asLong(); else usageComplete=false;
            JsonNode output=response.path("choices").path(0).path("message");
            if(!output.isObject()) throw new IllegalStateException("INVALID_MODEL_RESPONSE");
            var toolCalls=output.path("tool_calls");
            if(!toolCalls.isMissingNode() && !toolCalls.isNull() && !toolCalls.isArray()) throw new IllegalStateException("INVALID_TOOL_CALLS");
            if(!toolCalls.isArray() || toolCalls.isEmpty()) {
                String text=output.path("content").asText("");
                if(text.isBlank()) throw new IllegalStateException("EMPTY_MODEL_RESPONSE");
                return new Answer(text,usageComplete?tokens:null,round);
            }
            messages.add(JSON.convertValue(output,Map.class));
            for(var call:toolCalls) {
                if(++calls>7) throw new IllegalStateException("TOOL_BUDGET_EXCEEDED");
                String name=call.path("function").path("name").asText(), arguments=call.path("function").path("arguments").asText();
                if(!allowed.containsKey(name)) throw new IllegalStateException("UNREGISTERED_TOOL");
                if(call.path("id").asText().isBlank() || !JSON.readTree(arguments).isObject()) throw new IllegalStateException("INVALID_TOOL_ARGUMENTS");
                String result=invoke.apply(name,arguments);
                messages.add(Map.of("role","tool","tool_call_id",call.path("id").asText(),"content",result));
            }
        }
        throw new IllegalStateException("MODEL_ROUND_LIMIT");
    }
    private static String required(String name) { String v=System.getenv(name); if(v==null || v.isBlank()) throw new IllegalArgumentException("Missing "+name); return v; }
}
