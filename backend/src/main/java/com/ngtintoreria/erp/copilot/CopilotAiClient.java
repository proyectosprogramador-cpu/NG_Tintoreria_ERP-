package com.ngtintoreria.erp.copilot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * Conecta NG Copiloto con un proveedor de IA compatible con /chat/completions.
 * Conexión desactivada por defecto. Usa hechos filtrados por permisos en CopilotService,
 * sin conexión SQL, credenciales del ERP ni herramientas de escritura.
 */
@Component
public class CopilotAiClient {
    private final boolean enabled;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final ObjectMapper mapper;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public CopilotAiClient(ObjectMapper mapper,
                           @Value("${app.copilot.llm.enabled:false}") boolean enabled,
                           @Value("${app.copilot.llm.base-url:}") String baseUrl,
                           @Value("${app.copilot.llm.api-key:}") String apiKey,
                           @Value("${app.copilot.llm.model:}") String model) {
        this.mapper=mapper;this.enabled=enabled&&baseUrl!=null&&!baseUrl.isBlank()&&apiKey!=null&&!apiKey.isBlank()&&model!=null&&!model.isBlank();
        this.baseUrl=baseUrl==null?"":baseUrl.trim();this.apiKey=apiKey==null?"":apiKey.trim();this.model=model==null?"":model.trim();
    }

    public boolean enabled(){return enabled;}

    public String generate(String question,String context,String facts,String fallback){
        if(!enabled) return null;
        try{
            String system="Eres NG Copiloto Industrial dentro de una tintorería. Responde en español peruano, breve y operativo. "+
                    "Usa únicamente los HECHOS AUTORIZADOS proporcionados; no inventes datos ni afirmes haber ejecutado acciones. "+
                    "Si faltan datos, dilo. No des instrucciones para saltar bloqueos, permisos, calidad o auditoría. Contexto de pantalla: "+context+".";
            String user="CONSULTA: "+question+"\n\nHECHOS AUTORIZADOS:\n"+(facts==null||facts.isBlank()?"(sin hechos adicionales)":facts)+"\n\nRESPUESTA BASE DEL ERP:\n"+fallback;
            Map<String,Object> body=new LinkedHashMap<>();body.put("model",model);body.put("temperature",0.2);body.put("max_tokens",420);
            body.put("messages",List.of(Map.of("role","system","content",system),Map.of("role","user","content",user)));
            String endpoint=baseUrl.endsWith("/")?baseUrl+"chat/completions":baseUrl+"/chat/completions";
            HttpRequest req=HttpRequest.newBuilder(URI.create(endpoint)).timeout(Duration.ofSeconds(20))
                    .header("Authorization","Bearer "+apiKey).header("Content-Type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
            HttpResponse<String> res=http.send(req,HttpResponse.BodyHandlers.ofString());
            if(res.statusCode()<200||res.statusCode()>=300)return null;
            JsonNode root=mapper.readTree(res.body());
            JsonNode content=root.path("choices").path(0).path("message").path("content");
            return content.isTextual()?content.asText():null;
        }catch(Exception ignored){return null;}
    }
}
