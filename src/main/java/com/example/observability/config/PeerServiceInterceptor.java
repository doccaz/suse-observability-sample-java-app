package com.example.observability.config;

import io.opentelemetry.api.trace.Span;
import java.io.IOException;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Define o atributo "peer.service" (convenção semântica OTel) no span de
 * cliente HTTP criado pela auto-instrumentação do RestTemplate. É uma das
 * dimensões usadas pelo conector stsservicegraph do OTel Collector do SUSE
 * Observability para nomear o nó "servidor" ao construir o service graph —
 * sem isso a chamada aparece no grafo sem um nome de serviço de destino
 * associado.
 */
public class PeerServiceInterceptor implements ClientHttpRequestInterceptor {

    private final String peerServiceName;

    public PeerServiceInterceptor(String peerServiceName) {
        this.peerServiceName = peerServiceName;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        Span.current().setAttribute("peer.service", peerServiceName);
        return execution.execute(request, body);
    }
}
