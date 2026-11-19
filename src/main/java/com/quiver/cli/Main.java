package com.quiver.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class Main {

    private static final String DEFAULT_HOST = "127.0.0.1";
    private static final int DEFAULT_PORT = 3672;
    private static final ObjectMapper JSON = new ObjectMapper();

    public static void main(String[] args) {
        int exitCode;
        try {
            exitCode = run(args);
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("quiver-cli: " + e.getMessage());
            exitCode = 1;
        }
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static int run(String[] args) throws IOException {
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
                case "-e", "--execute" -> {
                    if (++i == args.length) {
                        throw new IllegalArgumentException("missing QSL after " + args[i - 1]);
                    }
                    query = String.join(" ", remaining(args, i));
                    i = args.length;
                }
                default -> throw new IllegalArgumentException(
                        "usage: quiver-cli [--host HOST] [--port PORT] [-e QSL]");
            }
        }

        if (query != null) {
            JsonNode response = execute(host, port, query);
            printResponse(response, false);
            return response.path("ok").asBoolean() ? 0 : 1;
        }
        return interactive(host, port);
    }

    private static List<String> remaining(String[] args, int start) {
        List<String> values = new ArrayList<>();
        for (int i = start; i < args.length; i++) {
            values.add(args[i]);
        }
        return values;
    }

    private static int interactive(String host, int port) throws IOException {
        System.out.println("Connected to Quiver at " + host + ":" + port + ". Enter QSL; use \\q to quit.");
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = input.readLine()) != null) {
            if (line.equals("\\q")) {
                return 0;
            }
            if (!line.isBlank()) {
                printResponse(execute(host, port, line), true);
            }
        }
        return 0;
    }

    private static JsonNode execute(String host, int port, String query) throws IOException {
        try (Socket socket = new Socket(host, port);
             BufferedWriter writer = new BufferedWriter(
                     new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
            ObjectNode request = JSON.createObjectNode().put("query", query);
            writer.write(JSON.writeValueAsString(request));
            writer.newLine();
            writer.flush();
            String response = reader.readLine();
            if (response == null) {
                throw new IOException("server closed the connection without a response");
            }
            return JSON.readTree(response);
        }
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
