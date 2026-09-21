package com.example.observability;

import io.opentelemetry.api.trace.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Mesma lógica de {@link InventoryController}, mas enriquecendo o span
 * auto-instrumentado com atributos de negócio via API do OpenTelemetry.
 * Roda no mesmo perfil "inventory" (processo/JVM separado do order).
 */
@RestController
@Profile("inventory")
public class ManualInventoryController {

    private static final Logger logger = LoggerFactory.getLogger(ManualInventoryController.class);

    @GetMapping("/api/manual/inventory/{itemId}")
    public InventoryController.InventoryResponse checkInventory(@PathVariable String itemId) throws InterruptedException {
        logger.info("Verificando estoque para o item {}", itemId);

        Thread.sleep(150);

        boolean available = Math.abs(itemId.hashCode()) % 5 != 0;
        if (!available) {
            logger.warn("Item {} sem estoque disponível", itemId);
        }

        Span span = Span.current();
        span.setAttribute("inventory.item_id", itemId);
        span.setAttribute("inventory.available", available);

        return new InventoryController.InventoryResponse(itemId, available);
    }
}
