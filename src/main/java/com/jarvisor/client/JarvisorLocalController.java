package com.jarvisor.client;

import org.springframework.web.bind.annotation.*;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;

@RestController
public class JarvisorLocalController {

    @GetMapping("/jarvisor/health")
    public Object health() {
        return java.util.Map.of("status","UP","service", System.getProperty("spring.application.name","unknown"));
    }

    @GetMapping("/jarvisor/metrics")
    public Object metrics() {
        MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
        return java.util.Map.of(
            "heapUsed", mem.getHeapMemoryUsage().getUsed(),
            "heapMax", mem.getHeapMemoryUsage().getMax(),
            "threads", ManagementFactory.getThreadMXBean().getThreadCount(),
            "uptime", ManagementFactory.getRuntimeMXBean().getUptime()
        );
    }
}
