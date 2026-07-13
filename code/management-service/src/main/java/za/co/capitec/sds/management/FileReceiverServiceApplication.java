package za.co.capitec.sds.management;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class FileReceiverServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(FileReceiverServiceApplication.class, args);
    }
}
