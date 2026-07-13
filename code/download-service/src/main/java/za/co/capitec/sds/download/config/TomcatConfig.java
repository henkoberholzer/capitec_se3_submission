package za.co.capitec.sds.download.config;

import org.apache.coyote.ProtocolHandler;
import org.springframework.boot.tomcat.TomcatProtocolHandlerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.VirtualThreadTaskExecutor;

/**
 * Enable virtual threads for the embedded Tomcat server.
 * Virtual threads are ideal for I/O-bound workloads (streaming downloads).
 * Each HTTP request runs on a lightweight virtual thread instead of a
 * heavyweight platform thread, allowing efficient handling of many concurrent downloads.
 */
@Configuration
public class TomcatConfig {

    @Bean
    public TomcatProtocolHandlerCustomizer<ProtocolHandler> virtualThreadProtocolHandlerCustomizer() {
        return protocolHandler ->
                protocolHandler.setExecutor(new VirtualThreadTaskExecutor("tomcat-handler-"));
    }
}
