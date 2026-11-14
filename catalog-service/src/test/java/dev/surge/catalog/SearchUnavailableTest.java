package dev.surge.catalog;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.surge.catalog.error.ApiExceptionHandler;
import dev.surge.catalog.search.EventSearchService;
import dev.surge.catalog.search.SearchController;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.data.elasticsearch.client.ClientConfiguration;
import org.springframework.data.elasticsearch.client.elc.ElasticsearchConfiguration;
import org.springframework.data.elasticsearch.client.elc.ElasticsearchTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** No Spring context: a search service pointed at a port where nothing listens must answer 503, never 500. */
class SearchUnavailableTest {

    @Test
    void elasticsearchDownGives503SearchUnavailable() throws Exception {
        ClientConfiguration clientConfiguration = ClientConfiguration.builder().connectedTo("localhost:1")
                .withConnectTimeout(Duration.ofSeconds(1)).withSocketTimeout(Duration.ofSeconds(1)).build();
        ElasticsearchConfiguration configuration = new ElasticsearchConfiguration() {
            @Override
            public ClientConfiguration clientConfiguration() {
                return clientConfiguration;
            }
        };
        var client = configuration.elasticsearchClient(configuration.elasticsearchTransport(
                configuration.elasticsearchRest5Client(clientConfiguration), configuration.jsonpMapper()));
        var conversions = configuration.elasticsearchCustomConversions();
        var converter = configuration.elasticsearchEntityMapper(
                configuration.elasticsearchMappingContext(conversions), conversions);
        MockMvc mvc = MockMvcBuilders
                .standaloneSetup(new SearchController(new EventSearchService(new ElasticsearchTemplate(client, converter))))
                .setControllerAdvice(new ApiExceptionHandler()).build();

        mvc.perform(get("/api/catalog/events/search").param("q", "coldplay"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value("https://surge.dev/problems/search-unavailable"))
                .andExpect(jsonPath("$.requestId").exists());
        // validation still comes first, with no call to Elasticsearch
        mvc.perform(get("/api/catalog/events/search").param("size", "500"))
                .andExpect(status().isBadRequest());
    }
}
