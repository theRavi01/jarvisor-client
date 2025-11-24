package com.jarvisor.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.io.input.Tailer;
import org.apache.commons.io.input.TailerListenerAdapter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import javax.annotation.PostConstruct;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.*;
import java.util.Timer;
import java.util.TimerTask;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;
import java.io.File;

@Configuration
@ConditionalOnProperty(prefix="jarvisor", name="enabled", havingValue="true")
public class JarvisorClientAutoConfig {

    @Value("${jarvisor.server-url}")
    private String serverUrl;

    @Value("${jarvisor.service-id}")
    private String serviceId;

    @Value("${jarvisor.service-name:${spring.application.name:unknown}}")
    private String serviceName;

    @Value("${jarvisor.secret-key}")
    private String secret;

    @Value("${jarvisor.log-file-path:}")
    private String logFilePath;

    private final ObjectMapper mapper = new ObjectMapper();

    @PostConstruct
    public void init() {
        try { register(); } catch (Exception e) { e.printStackTrace(); }
        startHeartbeat();
        if (logFilePath!=null && !logFilePath.isBlank()) startTailer(logFilePath);
    }

    private void register() throws Exception {
        Map<String,Object> payload = new HashMap<>();
        payload.put("serviceId", serviceId);
        payload.put("serviceName", serviceName);
        payload.put("host", java.net.InetAddress.getLocalHost().getHostAddress());
        payload.put("port", Integer.getInteger("server.port", 8080));
        payload.put("secret", secret);
        String json = mapper.writeValueAsString(payload);
        postJson(serverUrl + "/api/clients/register", json, null);
    }

    private void startHeartbeat() {
        Timer timer = new Timer(true);
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                try {
                    sendHeartbeat();
                } catch (Exception e) { e.printStackTrace(); }
            }
        }, 3000, 15000);
    }

    private void sendHeartbeat() throws Exception {
        Map<String,Object> payload = new HashMap<>();
        payload.put("serviceId", serviceId);
        payload.put("serviceName", serviceName);
        payload.put("uptime", ManagementFactory.getRuntimeMXBean().getUptime());
        payload.put("threads", ManagementFactory.getThreadMXBean().getThreadCount());
        MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
        payload.put("heapUsed", mem.getHeapMemoryUsage().getUsed());
        payload.put("heapMax", mem.getHeapMemoryUsage().getMax());
        try {
            com.sun.management.OperatingSystemMXBean os =
                (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            payload.put("processCpuLoad", os.getProcessCpuLoad());
            payload.put("systemCpuLoad", os.getSystemCpuLoad());
        } catch (Throwable t) {}
        payload.put("ts", System.currentTimeMillis());
        String json = mapper.writeValueAsString(payload);
        Map<String,String> headers = headers(json);
        postJson(serverUrl + "/api/clients/heartbeat", json, headers);
    }

    private Map<String,String> headers(String body) throws Exception {
        Map<String,String> h = new HashMap<>();
        long ts = System.currentTimeMillis();
        h.put("X-Jarvisor-ServiceId", serviceId);
        h.put("X-Jarvisor-Timestamp", String.valueOf(ts));
        h.put("X-Jarvisor-Signature", sign(secret, String.valueOf(ts) + "." + body));
        return h;
    }

    private void startTailer(String path) {
        try {
            File f = new File(path);
            if (!f.exists()) return;
            TailerListenerAdapter listener = new TailerListenerAdapter() {
                @Override
                public void handle(String line) {
                    try {
                        Map<String,Object> payload = Map.of(
                            "serviceId", serviceId,
                            "lines", List.of(Map.of("ts", System.currentTimeMillis(), "text", line))
                        );
                        String json = mapper.writeValueAsString(payload);
                        postJson(serverUrl + "/api/clients/logs", json, null);
                    } catch (Exception e) { e.printStackTrace(); }
                }
            };
            Tailer.create(f, listener, 1000, true);
        } catch (Exception e) { e.printStackTrace(); }
    }

    private void postJson(String urlStr, String json, Map<String,String> headers) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection)url.openConnection();
        conn.setConnectTimeout(4000);
        conn.setReadTimeout(4000);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type","application/json");
        if (headers!=null) for (var e: headers.entrySet()) conn.setRequestProperty(e.getKey(), e.getValue());
        conn.setDoOutput(true);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(json.getBytes(StandardCharsets.UTF_8));
        }
        int code = conn.getResponseCode();
        conn.disconnect();
    }

    private String sign(String secret, String message) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        byte[] digest = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(digest);
    }
}
