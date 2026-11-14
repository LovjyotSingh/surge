package dev.surge.payment;

import org.springframework.stereotype.Component;

@Component
public class ChaosSettings {
    private volatile Chaos current = Chaos.OFF;

    public Chaos get() {
        return current;
    }

    public void set(Chaos chaos) {
        this.current = chaos;
    }
}
