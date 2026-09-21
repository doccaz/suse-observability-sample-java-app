package com.example.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ObservabilityTestApplication implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(ObservabilityTestApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(ObservabilityTestApplication.class, args);
    }

    @Override
    public void run(String... args) throws Exception {
        logger.info(">>> Aplicação de teste de Observabilidade Iniciada (SLE BCI) <<<");
        int contador = 1;

        while (true) {
            logger.info("Mensagem de rotina #{}", contador);

            if (contador % 5 == 0) {
                logger.warn("Aviso: Validação de fluxo prod-homolog #{}", contador);
            }

            if (contador % 10 == 0) {
                logger.error("Erro simulado para validação de logs no SUSE Observability #{}", contador);
            }

            contador++;
            Thread.sleep(5000); // Gera log a cada 5 segundos
        }
    }
}
