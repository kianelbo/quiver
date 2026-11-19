package com.quiver.server;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.quiver.QuiverDatabase;
import com.quiver.core.instance.FsmInstance;
import com.quiver.protocol.PasswordAuth;
import com.quiver.query.exec.QueryResult;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Line-delimited JSON TCP endpoint. Each request is an object containing a QSL
 * {@code query}; each response contains either {@code results} or an {@code error}.
 */
public final class TcpServer implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final QuiverDatabase database;
    private final ServerSocket serverSocket;
    private final byte[] passwordSalt;
    private final byte[] passwordKey;
    private final ExecutorService clients = Executors.newVirtualThreadPerTaskExecutor();
    private final Set<Socket> openSockets = ConcurrentHashMap.newKeySet();
    private boolean closed;

    public TcpServer(QuiverDatabase database, String host, int port, String password) throws IOException {
        this.database = database;
        this.passwordSalt = PasswordAuth.newSalt();
        this.passwordKey = PasswordAuth.deriveKey(password, passwordSalt);
        this.serverSocket = new ServerSocket(port, 50, InetAddress.getByName(host));
    }

    public String address() {
        return serverSocket.getInetAddress().getHostAddress() + ":" + serverSocket.getLocalPort();
    }

    public int port() {
        return serverSocket.getLocalPort();
    }

    public void serve() throws IOException {
        while (!serverSocket.isClosed()) {
            try {
                Socket socket = serverSocket.accept();
                openSockets.add(socket);
                clients.submit(() -> handle(socket));
            } catch (IOException e) {
                if (!serverSocket.isClosed()) {
                    throw e;
                }
            }
        }
    }

    private void handle(Socket socket) {
        try (socket;
             BufferedReader reader = socketReader(socket);
             BufferedWriter writer = socketWriter(socket)) {
            byte[] nonce = PasswordAuth.newNonce();
            ObjectNode challenge = JSON.createObjectNode()
                    .put("type", "auth_challenge")
                    .put("nonce", java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(nonce))
                    .put("salt", java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(passwordSalt));
            writer.write(JSON.writeValueAsString(challenge));
            writer.newLine();
            writer.flush();

            String authenticationRequest = reader.readLine();
            if (!authenticate(authenticationRequest, nonce, writer)) {
                return;
            }

            String line;
            while ((line = reader.readLine()) != null) {
                ObjectNode response;
                try {
                    JsonNode request = JSON.readTree(line);
                    JsonNode queryNode = request == null ? null : request.get("query");
                    if (queryNode == null || !queryNode.isTextual() || queryNode.textValue().isBlank()) {
                        throw new IllegalArgumentException("request must contain a non-empty string field 'query'");
                    }
                    response = successfulResponse(database.executeScript(queryNode.textValue()));
                } catch (JsonProcessingException e) {
                    response = errorResponse("invalid JSON request: " + e.getOriginalMessage());
                } catch (RuntimeException e) {
                    response = errorResponse(errorMessage(e));
                }
                writer.write(JSON.writeValueAsString(response));
                writer.newLine();
                writer.flush();
            }
        } catch (IOException e) {
            if (!socket.isClosed()) {
                System.err.println("Quiver client connection failed: " + e.getMessage());
            }
        } finally {
            openSockets.remove(socket);
        }
    }

    private boolean authenticate(String requestLine, byte[] nonce, BufferedWriter writer) throws IOException {
        String proof = null;
        if (requestLine != null) {
            try {
                JsonNode request = JSON.readTree(requestLine);
                if (request != null && "authenticate".equals(request.path("type").asText())) {
                    proof = request.path("proof").asText(null);
                }
            } catch (JsonProcessingException ignored) {
                // Invalid authentication messages receive the same generic failure as wrong passwords.
            }
        }
        if (!PasswordAuth.verifyProof(passwordKey, nonce, proof)) {
            writer.write(JSON.createObjectNode().put("ok", false).put("error", "authentication failed").toString());
            writer.newLine();
            writer.flush();
            return false;
        }
        writer.write(JSON.createObjectNode().put("ok", true).put("authenticated", true).toString());
        writer.newLine();
        writer.flush();
        return true;
    }

    private static BufferedReader socketReader(Socket socket) throws IOException {
        return new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
    }

    private static BufferedWriter socketWriter(Socket socket) throws IOException {
        return new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
    }

    private static ObjectNode successfulResponse(Iterable<QueryResult> results) {
        ObjectNode response = JSON.createObjectNode().put("ok", true);
        ArrayNode resultNodes = response.putArray("results");
        results.forEach(result -> resultNodes.add(serializeResult(result)));
        return response;
    }

    private static JsonNode serializeResult(QueryResult result) {
        ObjectNode node = JSON.createObjectNode();
        switch (result) {
            case QueryResult.Ack ack -> node.put("type", "ack").put("message", ack.message());
            case QueryResult.InstanceResult instanceResult ->
                    node.put("type", "instance").set("instance", serializeInstance(instanceResult.instance()));
            case QueryResult.InstanceListResult listResult -> {
                node.put("type", "instances");
                ArrayNode instances = node.putArray("instances");
                listResult.instances().forEach(instance -> instances.add(serializeInstance(instance)));
            }
            case QueryResult.TransactionResult transactionResult -> {
                node.put("type", "transaction");
                ArrayNode innerResults = node.putArray("results");
                transactionResult.results().forEach(inner -> innerResults.add(serializeResult(inner)));
            }
        }
        return node;
    }

    private static ObjectNode serializeInstance(FsmInstance instance) {
        ObjectNode node = JSON.createObjectNode()
                .put("fsmName", instance.fsmName())
                .put("instanceId", instance.instanceId())
                .put("version", instance.version())
                .put("createdAt", instance.createdAt().toString());
        node.set("currentStates", JSON.valueToTree(instance.currentStates()));
        node.set("attributes", JSON.valueToTree(instance.attributes()));
        ArrayNode history = node.putArray("history");
        instance.history().forEach(entry -> {
            ObjectNode historyEntry = history.addObject()
                    .put("input", entry.input())
                    .put("timestamp", entry.timestamp().toString());
            historyEntry.set("resultingStates", JSON.valueToTree(entry.resultingStates()));
        });
        return node;
    }

    private static ObjectNode errorResponse(String message) {
        return JSON.createObjectNode().put("ok", false).put("error", message);
    }

    private static String errorMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            serverSocket.close();
        } catch (IOException e) {
            throw new IllegalStateException("failed to close Quiver TCP server", e);
        } finally {
            openSockets.forEach(socket -> {
                try {
                    socket.close();
                } catch (IOException e) {
                    System.err.println("Failed to close Quiver client socket: " + e.getMessage());
                }
            });
            clients.close();
            Arrays.fill(passwordKey, (byte) 0);
        }
    }
}
