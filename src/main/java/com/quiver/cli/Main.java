package com.quiver.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.quiver.protocol.PasswordAuth;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.URI;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

public final class Main {

    private static final String DEFAULT_HOST = "127.0.0.1";
    private static final int DEFAULT_PORT = 3672;
    private static final ObjectMapper JSON = new ObjectMapper();

    public static void main(String[] args) {
        int exitCode;
        try {
            exitCode = run(args, resolvePassword());
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("quiver-cli: " + e.getMessage());
            exitCode = 1;
        }
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static int run(String[] args, String password) throws IOException {
        PasswordAuth.validatePassword(password);
        String host = DEFAULT_HOST;
        int port = DEFAULT_PORT;
        String query = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--host" -> {
                    if (++i == args.length) {
                        throw new IllegalArgumentException("missing value for --host");
                    }
                    host = args[i];
                }
                case "--port" -> {
                    if (++i == args.length) {
                        throw new IllegalArgumentException("missing value for --port");
                    }
                    port = parsePort(args[i]);
                }
                case "--url" -> {
                    if (++i == args.length) {
                        throw new IllegalArgumentException("missing value for --url");
                    }
                    URI url = parseUrl(args[i]);
                    host = url.getHost();
                    port = url.getPort() == -1 ? DEFAULT_PORT : url.getPort();
                }
                case "-e", "--execute" -> {
                    if (++i == args.length) {
                        throw new IllegalArgumentException("missing QSL after " + args[i - 1]);
                    }
                    query = String.join(" ", remaining(args, i));
                    i = args.length;
                }
                default -> throw new IllegalArgumentException(
                        "usage: quiver-cli [--url quiver://HOST[:PORT]] [--host HOST] [--port PORT] [-e QSL]");
            }
        }

        if (query != null) {
            JsonNode response = execute(host, port, password, query);
            printResponse(response, false);
            return response.path("ok").asBoolean() ? 0 : 1;
        }
        return interactive(host, port, password);
    }

    static int run(String[] args) throws IOException {
        return run(args, resolvePassword());
    }

    private static String resolvePassword() {
        String password = System.getenv("QUIVER_PASSWORD");
        if (password != null && !password.isBlank()) {
            return password;
        }
        java.io.Console console = System.console();
        if (console == null) {
            throw new IllegalArgumentException(
                    "set QUIVER_PASSWORD or run the CLI in a terminal to enter the password securely");
        }
        char[] entered = console.readPassword("Quiver password: ");
        if (entered == null || entered.length == 0) {
            throw new IllegalArgumentException("password must not be empty");
        }
        try {
            return new String(entered);
        } finally {
            Arrays.fill(entered, '\0');
        }
    }

    private static URI parseUrl(String value) {
        URI url;
        try {
            url = URI.create(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid Quiver URL: " + value, e);
        }
        if (!"quiver".equals(url.getScheme()) || url.getHost() == null
                || (url.getPort() != -1 && (url.getPort() < 1 || url.getPort() > 65535))
                || url.getRawUserInfo() != null
                || (url.getPath() != null && !url.getPath().isEmpty())
                || url.getQuery() != null || url.getFragment() != null) {
            throw new IllegalArgumentException("URL must have the form quiver://HOST[:PORT]");
        }
        return url;
    }

    private static List<String> remaining(String[] args, int start) {
        List<String> values = new ArrayList<>();
        for (int i = start; i < args.length; i++) {
            values.add(args[i]);
        }
        return values;
    }

    private static int interactive(String host, int port, String password) throws IOException {
        verifyConnection(host, port, password);
        System.out.println("Authenticated to Quiver at " + host + ":" + port + ". Enter QSL; use \\q to quit.");
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = input.readLine()) != null) {
            if (line.equals("\\q")) {
                return 0;
            }
            if (!line.isBlank()) {
                printResponse(execute(host, port, password, line), true);
            }
        }
        return 0;
    }

    private static JsonNode execute(String host, int port, String password, String query) throws IOException {
        try (Socket socket = new Socket(host, port);
             BufferedWriter writer = new BufferedWriter(
                     new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
            authenticate(reader, writer, password);
            ObjectNode request = JSON.createObjectNode().put("query", query);
            writer.write(JSON.writeValueAsString(request));
            writer.newLine();
            writer.flush();
            return readResponse(reader, "server closed the connection without a response");
        }
    }

    private static void verifyConnection(String host, int port, String password) throws IOException {
        try (Socket socket = new Socket(host, port);
             BufferedWriter writer = new BufferedWriter(
                     new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
            authenticate(reader, writer, password);
        }
    }

    private static void authenticate(BufferedReader reader, BufferedWriter writer, String password)
            throws IOException {
        JsonNode challenge = readResponse(reader, "server did not send an authentication challenge");
        if (!"auth_challenge".equals(challenge.path("type").asText())) {
            throw new IOException("server sent an invalid authentication challenge");
        }
        byte[] nonce;
        byte[] salt;
        try {
            nonce = Base64.getUrlDecoder().decode(challenge.path("nonce").asText());
            salt = Base64.getUrlDecoder().decode(challenge.path("salt").asText());
        } catch (IllegalArgumentException e) {
            throw new IOException("server sent an invalid authentication challenge", e);
        }
        byte[] passwordKey = PasswordAuth.deriveKey(password, salt);
        String proof;
        try {
            proof = PasswordAuth.createProof(passwordKey, nonce);
        } finally {
            Arrays.fill(passwordKey, (byte) 0);
        }
        ObjectNode authentication = JSON.createObjectNode()
                .put("type", "authenticate")
                .put("proof", proof);
        writer.write(JSON.writeValueAsString(authentication));
        writer.newLine();
        writer.flush();

        JsonNode result = readResponse(reader, "server closed the connection during authentication");
        if (!result.path("ok").asBoolean()) {
            throw new IOException(result.path("error").asText("authentication failed"));
        }
    }

    private static JsonNode readResponse(BufferedReader reader, String eofMessage) throws IOException {
        String response = reader.readLine();
        if (response == null) {
            throw new IOException(eofMessage);
        }
        return JSON.readTree(response);
    }

    private static void printResponse(JsonNode response, boolean continueOnError) throws IOException {
        if (!response.path("ok").asBoolean()) {
            System.err.println(response.path("error").asText("server returned an unspecified error"));
            if (continueOnError) {
                return;
            }
        } else {
            System.out.println(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(response.path("results")));
        }
    }

    private static int parsePort(String value) {
        try {
            int port = Integer.parseInt(value);
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("port must be between 1 and 65535");
            }
            return port;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid port: " + value, e);
        }
    }
}
