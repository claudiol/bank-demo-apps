package com.northwater;

import io.javalin.Javalin;
import java.nio.file.Path;

public final class Northwater {
    private Northwater() {}

    @SuppressWarnings({"PMD.AvoidUsingHardCodedIP", "PMD.SystemPrintln"})
    public static void main(String[] args) {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        Path database = Path.of(System.getenv().getOrDefault("NORTHWATER_DB", "data/northwater.db"));
        Javalin app = BankApp.create(database);
        app.start("127.0.0.1", port);
        System.out.println("Northwater demo is running at http://127.0.0.1:" + port);
        System.out.println("Avery Chen    avery@northwater.demo");
        System.out.println("Jordan Hale   jordan@northwater.demo");
        System.out.println("Password      " + SeedData.DEMO_PASSWORD);
    }
}
