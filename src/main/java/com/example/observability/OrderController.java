package com.example.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

/**
 * Roda como o serviço "order" (perfil Spring "order"), um processo/JVM
 * separado do {@link InventoryController}, para que a chamada HTTP entre
 * eles apareça no service graph do OTel como uma relação entre dois
 * serviços de verdade (evita o self-loop de client==server no mesmo
 * service.instance.id).
 */
@RestController
@Profile("order")
public class OrderController {

    private static final Logger logger = LoggerFactory.getLogger(OrderController.class);

    private final RestTemplate restTemplate;

    @Value("${inventory.service.url:http://localhost:8081}")
    private String inventoryServiceUrl;

    public OrderController(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @GetMapping("/api/order/{itemId}")
    public String placeOrder(@PathVariable String itemId) {
        logger.info("Recebido pedido para o item {}", itemId);

        String inventoryUrl = inventoryServiceUrl + "/api/inventory/" + itemId;
        InventoryController.InventoryResponse inventory =
                restTemplate.getForObject(inventoryUrl, InventoryController.InventoryResponse.class);

        if (inventory != null && inventory.available()) {
            logger.info("Pedido do item {} confirmado", itemId);
            return "Pedido confirmado para o item " + itemId;
        }

        logger.error("Falha ao confirmar pedido do item {}: sem estoque", itemId);
        return "Pedido do item " + itemId + " não pôde ser confirmado (sem estoque)";
    }
}
