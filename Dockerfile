# Estágio de Execução utilizando o SUSE Linux Enterprise BCI para OpenJDK 17
FROM registry.suse.com/bci/openjdk:17

# Define o diretório de trabalho
WORKDIR /app

# Cria o diretório para o agente do OpenTelemetry
RUN mkdir -p /otel

# Baixa o Agente OpenTelemetry usando curl (presente no BCI)
RUN curl -L -o /otel/opentelemetry-javaagent.jar https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/latest/download/opentelemetry-javaagent.jar

# Copia o JAR compilado da sua aplicação
COPY target/observability-test-0.0.1-SNAPSHOT.jar /app/app.jar

# (Opcional, mas recomendado) Cria um usuário não-root por segurança
RUN useradd -m appuser \
    && chown -R appuser /app /otel \
    && chmod 644 /otel/opentelemetry-javaagent.jar

# Alterna para o usuário restrito
USER appuser

EXPOSE 8080

# Define o ponto de entrada usando o Java Agent
ENTRYPOINT ["java", "-javaagent:/otel/opentelemetry-javaagent.jar", "-jar", "/app/app.jar"]
