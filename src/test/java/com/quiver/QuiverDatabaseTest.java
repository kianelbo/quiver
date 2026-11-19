package com.quiver;

import com.quiver.query.exec.QueryResult;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class QuiverDatabaseTest {

    @Test
    void ephemeralDatabaseWorksEndToEnd() {
        try (QuiverDatabase db = QuiverDatabase.ephemeral()) {
            db.execute("DEFINE FSM order (STATES (created, paid), INITIAL created, ALPHABET (pay), TRANSITIONS (created (pay): paid))");
            db.execute("CREATE order:o1");
            var result = (QueryResult.InstanceResult) db.execute("order:o1(pay)");
            assertEquals(Set.of("paid"), result.instance().currentStates());
        }
    }

    @Test
    void durableDatabaseSurvivesARestart() throws IOException {
        Path dir = Files.createTempDirectory("quiver-db-test");
        Path walPath = dir.resolve("quiver.wal");
        try {
            try (QuiverDatabase db = QuiverDatabase.open(walPath)) {
                db.execute("DEFINE FSM order (STATES (created, paid, shipped), INITIAL created, ALPHABET (pay, ship), TRANSITIONS (created (pay): paid, paid (ship): shipped))");
                db.execute("CREATE order:o1");
                db.execute("order:o1(pay)");
            } // simulates a process restart: engine closed, WAL file remains on disk

            try (QuiverDatabase db = QuiverDatabase.open(walPath)) {
                var result = (QueryResult.InstanceResult) db.execute("GET order:o1");
                assertEquals(Set.of("paid"), result.instance().currentStates());
                assertEquals(java.util.List.of("pay"), result.instance().inputSequence());

                // and the reopened database is still fully live: further writes work too
                db.execute("order:o1(ship)");
                var after = (QueryResult.InstanceResult) db.execute("GET order:o1");
                assertEquals(Set.of("shipped"), after.instance().currentStates());
            }
        } finally {
            try (var files = Files.walk(dir)) {
                files.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try { Files.delete(p); } catch (IOException ignored) { }
                });
            }
        }
    }

    @Test
    void executeScriptRunsEachStatementAndReturnsOneResultPerStatement() {
        try (QuiverDatabase db = QuiverDatabase.ephemeral()) {
            var results = db.executeScript("""
                    DEFINE FSM order (STATES (created, paid), INITIAL created, ALPHABET (pay), TRANSITIONS (created (pay): paid));
                    CREATE order:o1;
                    order:o1(pay);
                    GET order:o1;
                    """);
            assertEquals(4, results.size());
            assertInstanceOf(QueryResult.Ack.class, results.get(0));
            assertInstanceOf(QueryResult.InstanceResult.class, results.get(3));
        }
    }
}
