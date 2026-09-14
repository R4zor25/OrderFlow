package com.orderflow.order;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import tools.jackson.databind.json.JsonMapper;

public final class KeycloakTokens {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private KeycloakTokens() {
    }

    public static String password(String username) {
        return token("grant_type=password&client_id=orderflow-cli&username=" + encode(username)
            + "&password=" + encode(KeycloakTestContainer.USER_PASSWORD));
    }

    public static String clientCredentials() {
        return token("grant_type=client_credentials&client_id=order-service&client_secret="
            + encode(KeycloakTestContainer.CLIENT_SECRET));
    }

    private static String token(String form) {
        var request = HttpRequest.newBuilder(URI.create(KeycloakTestContainer.realmUrl() + "/protocol/openid-connect/token"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form))
            .build();
        try {
            var response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Token request failed with " + response.statusCode() + ": " + response.body());
            }
            return JSON.readTree(response.body()).path("access_token").asString();
        } catch (IOException exception) {
            throw new IllegalStateException("Token request failed", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Token request interrupted", exception);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
