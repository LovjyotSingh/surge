package dev.surge.payment;

import java.util.random.RandomGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class PaymentApplication {
    public static void main(String[] args) {
        SpringApplication.run(PaymentApplication.class, args);
    }

    /** Injectable so chaos decisions can be made deterministic (rates of 0 or 1 never depend on the draw). */
    @Bean
    RandomGenerator randomGenerator() {
        return RandomGenerator.getDefault();
    }
}
