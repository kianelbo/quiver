package com.quiver;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

public final class Main {

    public static void main(String[] args) throws InterruptedException {
        if (args.length > 1) {
            throw new IllegalArgumentException("usage: quiver-server [path-to-wal-file]");
        }

        QuiverDatabase database = args.length == 1
                ? QuiverDatabase.open(Path.of(args[0]))
                : QuiverDatabase.ephemeral();
        Runtime.getRuntime().addShutdownHook(new Thread(database::close, "quiver-shutdown"));

        if (args.length == 1) {
            System.out.println("Quiver database started with WAL at '" + args[0] + "'.");
        } else {
            System.out.println("Quiver database started in ephemeral mode; data will not persist.");
        }

        new CountDownLatch(1).await();
    }
}
