package com.example.observability;

import io.opentelemetry.api.trace.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

/**
 * Mesma lógica de {@link OrderController}, mas enriquecendo o span
 * auto-instrumentado com atributos de negócio via API do OpenTelemetry.
 * Roda no mesmo perfil "order" (processo/JVM separado do inventory).
 */
@RestController
@Profile("order")
public class ManualOrderController {

    private static final Logger logger = LoggerFactory.getLogger(ManualOrderController.class);

    private final RestTemplate restTemplate;

    @Value("${inventory.service.url:http://localhost:8081}")
    private String inventoryServiceUrl;

    public ManualOrderController(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @GetMapping("/api/manual/order/{itemId}")
    public String placeOrder(@PathVariable String itemId) {
        logger.info("Recebido pedido para o item {}", itemId);

        Span span = Span.current();
        span.setAttribute("order.item_id", itemId);

        String inventoryUrl = inventoryServiceUrl + "/api/manual/inventory/" + itemId;
        InventoryController.InventoryResponse inventory =
                restTemplate.getForObject(inventoryUrl, InventoryController.InventoryResponse.class);

        boolean available = inventory != null && inventory.available();
        span.setAttribute("order.available", available);

        if (available) {
            logger.info("Pedido do item {} confirmado", itemId);
            span.setAttribute("order.status", "confirmed");
            return "Pedido confirmado para o item " + itemId;
        }

        logger.error("Falha ao confirmar pedido do item {}: sem estoque", itemId);
        span.setAttribute("order.status", "rejected");
        return "Pedido do item " + itemId + " não pôde ser confirmado (sem estoque)";
    }
}
