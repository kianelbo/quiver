package com.quiver.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quiver.QuiverDatabase;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TcpServerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void servesMultipleQueriesAndErrorsOverOneTcpConnection() throws Exception {
        try (QuiverDatabase database = QuiverDatabase.ephemeral();
             TcpServer server = new TcpServer(database, "127.0.0.1", 0)) {
            Thread serverThread = new Thread(() -> {
                try {
                    server.serve();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            serverThread.start();

            try (Socket socket = new Socket("127.0.0.1", server.port());
                 BufferedWriter writer = new BufferedWriter(
                         new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
                 BufferedReader reader = new BufferedReader(
                         new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
                send(writer, "DEFINE FSM order (STATES (created, paid), INITIAL created, ALPHABET (pay), TRANSITIONS (created (pay): paid))");
                assertTrue(response(reader).path("ok").asBoolean());

                send(writer, "CREATE order:o1");
                assertEquals("instance", response(reader).path("results").get(0).path("type").asText());

                send(writer, "order:o1(pay)");
                JsonNode transition = response(reader);
                assertTrue(transition.path("ok").asBoolean());
                assertEquals("paid", transition.path("results").get(0)
                        .path("instance").path("currentStates").get(0).asText());

                send(writer, "GET order:missing");
                JsonNode error = response(reader);
                assertTrue(!error.path("ok").asBoolean());
                assertTrue(error.path("error").asText().contains("missing"));
            }
        }
    }

    private static void send(BufferedWriter writer, String query) throws IOException {
        writer.write(JSON.createObjectNode().put("query", query).toString());
        writer.newLine();
        writer.flush();
    }

    private static JsonNode response(BufferedReader reader) throws IOException {
        return JSON.readTree(reader.readLine());
    }
}
