package dev.surge.booking.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import dev.surge.booking.event.Tier;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

class PricingBindingTest {

    private static List<PropertySource<?>> applicationYml() {
        try {
            return new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load application.yml", e);
        }
    }

    @Test
    void tierPricesBindFromRealApplicationYml() {
        TierPrices prices = new Binder(ConfigurationPropertySources.from(applicationYml()))
                .bind("surge.pricing", TierPrices.class)
                .get();

        assertThat(prices.base(Tier.PLATINUM)).isEqualByComparingTo("4999");
        assertThat(prices.base(Tier.GOLD)).isEqualByComparingTo("2999");
        assertThat(prices.base(Tier.SILVER)).isEqualByComparingTo("1499");
    }

    @Test
    void contextWiresTierPricesAndRulesInOrder() {
        new ApplicationContextRunner()
                .withInitializer(ctx -> applicationYml()
                        .forEach(s -> ctx.getEnvironment().getPropertySources().addLast(s)))
                .withUserConfiguration(PricingConfig.class, EarlyBirdRule.class, SurgeRule.class,
                        PriceCalculator.class)
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBean(TierPrices.class).base(Tier.GOLD)).isEqualByComparingTo("2999");
                    List<Class<?>> ruleTypes = ctx.getBeanProvider(PricingRule.class)
                            .orderedStream()
                            .<Class<?>>map(Object::getClass)
                            .toList();
                    assertThat(ruleTypes).containsExactly(EarlyBirdRule.class, SurgeRule.class);
                });
    }
}
