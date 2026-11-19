package com.quiver.cli;

import com.quiver.QuiverDatabase;
import com.quiver.server.TcpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CliTest {

    @Test
    void executesQslThroughTheTcpServer() throws Exception {
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

            assertEquals(0, Main.run(new String[]{
                    "--port", Integer.toString(server.port()), "-e", "DEFINE FSM order (INITIAL created, TRANSITIONS (created (pay): paid))"
            }));
            assertEquals(1, Main.run(new String[]{
                    "--port", Integer.toString(server.port()), "-e", "GET order:missing"
            }));
        }
    }
}
