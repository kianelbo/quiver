package com.quiver;

import com.quiver.server.TcpServer;

import java.nio.file.Path;
import java.io.IOException;

public final class Main {

    private static final String DEFAULT_HOST = "127.0.0.1";
    private static final int DEFAULT_PORT = 3672;

    public static void main(String[] args) throws IOException {
        String host = DEFAULT_HOST;
        int port = DEFAULT_PORT;
        Path walPath = null;

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
                default -> {
                    if (args[i].startsWith("--") || walPath != null) {
                        throw new IllegalArgumentException(
                                "usage: quiver-server [path-to-wal-file] [--host HOST] [--port PORT]");
                    }
                    walPath = Path.of(args[i]);
                }
            }
        }

        QuiverDatabase database = walPath != null
                ? QuiverDatabase.open(walPath)
                : QuiverDatabase.ephemeral();
        try (TcpServer server = new TcpServer(database, host, port)) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                server.close();
            }, "quiver-shutdown"));

            if (walPath != null) {
                System.out.println("Quiver database started with WAL at '" + walPath + "'.");
            } else {
                System.out.println("Quiver database started in ephemeral mode; data will not persist.");
            }
            System.out.println("Listening for TCP clients on " + server.address() + ".");
            server.serve();
        } finally {
            database.close();
        }
    }

    private static int parsePort(String value) {
        try {
            int port = Integer.parseInt(value);
            if (port < 0 || port > 65535) {
                throw new IllegalArgumentException("port must be between 0 and 65535");
            }
            return port;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid port: " + value, e);
        }
    }
}
