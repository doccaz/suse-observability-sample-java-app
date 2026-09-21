package com.example.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Roda como o serviço "inventory" (perfil Spring "inventory"), um
 * processo/JVM separado do {@link OrderController} — ver o motivo no
 * Javadoc de {@code OrderController}.
 */
@RestController
@Profile("inventory")
public class InventoryController {

    private static final Logger logger = LoggerFactory.getLogger(InventoryController.class);

    @GetMapping("/api/inventory/{itemId}")
    public InventoryResponse checkInventory(@PathVariable String itemId) throws InterruptedException {
        logger.info("Verificando estoque para o item {}", itemId);

        // Simula uma consulta a um serviço/banco de dados de estoque
        Thread.sleep(150);

        boolean available = Math.abs(itemId.hashCode()) % 5 != 0;
        if (!available) {
            logger.warn("Item {} sem estoque disponível", itemId);
        }

        return new InventoryResponse(itemId, available);
    }

    public record InventoryResponse(String itemId, boolean available) {
    }
}
