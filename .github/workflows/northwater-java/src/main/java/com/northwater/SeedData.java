package com.northwater;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Opening books for the two demonstration customers. */
public final class SeedData {
    public static final String DEMO_PASSWORD = "northwater";

    public record Person(String fullName, String email, String city, String memberSince) {}

    public static final Person AVERY = new Person("Avery Chen", "avery@northwater.demo", "Providence", "2019-03-12");
    public static final Person JORDAN = new Person("Jordan Hale", "jordan@northwater.demo", "Pawtucket", "2021-08-02");

    public record Row(String postedAt, String description, long amountCents, long balanceAfter, String category) {}

    public record Account(String nickname, String kind, String number, String openedOn, long balance, List<Row> rows) {}

    public record Customer(Person person, List<Account> accounts) {}

    private record Event(String date, String description, Long amount, String category) {}

    private SeedData() {}

    public static List<Customer> customers() {
        String today = LocalDate.now().toString();
        List<Event> averyChecking = new ArrayList<>(AVERY_CHECKING);
        List<Event> jordanChecking = new ArrayList<>(JORDAN_CHECKING);
        if ("2026-07-01".compareTo(today) <= 0) {
            averyChecking.add(new Event(today, "Little Peak Coffee", -475L, "dining"));
        }
        if ("2026-08-01".compareTo(today) <= 0) {
            jordanChecking.add(new Event(today, "Farmers market", -1_820L, "groceries"));
        }

        List<Row> averyCheckingRows = materialize(averyChecking);
        List<Row> averySavingsRows = materialize(AVERY_SAVINGS);
        List<Row> jordanCheckingRows = materialize(jordanChecking);
        List<Row> jordanSavingsRows = materialize(JORDAN_SAVINGS);
        if (net(averyCheckingRows, averySavingsRows, "internal") != 0) {
            throw new IllegalStateException("Avery's transfers between accounts do not balance");
        }
        if (net(jordanCheckingRows, jordanSavingsRows, "internal") != 0) {
            throw new IllegalStateException("Jordan's transfers between accounts do not balance");
        }
        if (net(List.of(averyCheckingRows, averySavingsRows, jordanCheckingRows, jordanSavingsRows), "transfer") != 0) {
            throw new IllegalStateException("Transfers between customers do not balance");
        }

        return List.of(
                new Customer(AVERY, List.of(
                        account("Everyday Checking", "checking", "4810291842", "2019-03-12", averyCheckingRows),
                        account("Reserve Savings", "savings", "4810299033", "2019-03-12", averySavingsRows))),
                new Customer(JORDAN, List.of(
                        account("Everyday Checking", "checking", "5501925510", "2021-08-02", jordanCheckingRows),
                        account("Rainy Day Savings", "savings", "5501927721", "2021-08-02", jordanSavingsRows))));
    }

    private static Account account(String nickname, String kind, String number, String openedOn, List<Row> rows) {
        long balance = rows.get(rows.size() - 1).balanceAfter();
        return new Account(nickname, kind, number, openedOn, balance, rows);
    }

    private static long net(List<Row> left, List<Row> right, String category) {
        return net(List.of(left, right), category);
    }

    private static long net(List<List<Row>> groups, String category) {
        long total = 0;
        for (List<Row> rows : groups) {
            for (Row row : rows) {
                if (category.equals(row.category())) {
                    total += row.amountCents();
                }
            }
        }
        return total;
    }

    private static List<Row> materialize(List<Event> events) {
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < events.size(); i++) {
            order.add(i);
        }
        order.sort(Comparator.comparing((Integer i) -> events.get(i).date()).thenComparingInt(i -> i));
        long balance = 0;
        List<Row> rows = new ArrayList<>();
        for (int index : order) {
            Event event = events.get(index);
            long amount = event.amount() == null ? monthlyInterest(balance) : event.amount();
            if (amount == 0) {
                continue;
            }
            balance += amount;
            if (balance < 0) {
                throw new IllegalStateException(event.description() + " on " + event.date() + " overdraws the account");
            }
            rows.add(new Row(event.date(), event.description(), amount, balance, event.category()));
        }
        return rows;
    }

    /** 3.80% APY, credited monthly and rounded half up to the cent. */
    private static long monthlyInterest(long balanceCents) {
        if (balanceCents <= 0) {
            return 0;
        }
        return (balanceCents * 19 + 3000) / 6000;
    }

    private static final List<Event> AVERY_CHECKING = List.of(
            event("2026-07-01", "Opening balance", 250_000, "opening"),
            event("2026-07-02", "Northwind Studio payroll", 428_000, "income"),
            event("2026-07-03", "Harbor Market", -8_640, "groceries"),
            event("2026-07-05", "Linden Apartments", -185_000, "housing"),
            event("2026-07-08", "City Power & Light", -14_220, "utilities"),
            event("2026-07-11", "Metro Transit", -9_200, "transport"),
            event("2026-07-16", "Northwind Studio payroll", 428_000, "income"),
            event("2026-07-18", "Transfer to Reserve Savings", -100_000, "internal"),
            event("2026-07-22", "Elm Street Books", -3_480, "shopping"),
            event("2026-07-30", "Northwind Studio payroll", 428_000, "income"),
            event("2026-08-02", "Linden Apartments", -185_000, "housing"),
            event("2026-08-04", "Harbor Market", -11_230, "groceries"),
            event("2026-08-09", "Cedar Clinic", -4_500, "health"),
            event("2026-08-13", "Northwind Studio payroll", 428_000, "income"),
            event("2026-08-15", "Transfer to Reserve Savings", -150_000, "internal"),
            event("2026-08-21", "Oat & Rye", -1_860, "dining"),
            event("2026-08-27", "Northwind Studio payroll", 428_000, "income"),
            event("2026-09-02", "Linden Apartments", -185_000, "housing"),
            event("2026-09-03", "City Power & Light", -13_640, "utilities"),
            event("2026-09-10", "Northwind Studio payroll", 428_000, "income"),
            event("2026-09-14", "Fee reversal", 1_500, "adjustment"),
            event("2026-09-18", "Harbor Market", -9_740, "groceries"),
            event("2026-09-24", "Northwind Studio payroll", 428_000, "income"),
            event("2026-09-28", "Transfer to Reserve Savings", -200_000, "internal"),
            event("2026-10-02", "Linden Apartments", -185_000, "housing"),
            event("2026-10-08", "Northwind Studio payroll", 428_000, "income"),
            event("2026-10-12", "Field & Vine", -6_420, "dining"),
            event("2026-10-16", "Received from Jordan Hale", 7_500, "transfer"),
            event("2026-10-22", "Northwind Studio payroll", 428_000, "income"));

    private static final List<Event> AVERY_SAVINGS = List.of(
            event("2026-07-01", "Opening balance", 1_800_000, "opening"),
            event("2026-07-18", "Transfer from Everyday Checking", 100_000, "internal"),
            interest("2026-08-01"),
            event("2026-08-15", "Transfer from Everyday Checking", 150_000, "internal"),
            interest("2026-09-01"),
            event("2026-09-28", "Transfer from Everyday Checking", 200_000, "internal"),
            interest("2026-10-01"));

    private static final List<Event> JORDAN_CHECKING = List.of(
            event("2026-08-01", "Opening balance", 410_000, "opening"),
            event("2026-08-03", "Hale Ceramics payroll", 364_000, "income"),
            event("2026-08-04", "Rent", -160_000, "housing"),
            event("2026-08-17", "Hale Ceramics payroll", 364_000, "income"),
            event("2026-08-20", "Market basket", -7_820, "groceries"),
            event("2026-08-31", "Hale Ceramics payroll", 364_000, "income"),
            event("2026-09-04", "Rent", -160_000, "housing"),
            event("2026-09-14", "Hale Ceramics payroll", 364_000, "income"),
            event("2026-09-19", "Studio electric", -9_430, "utilities"),
            event("2026-09-28", "Hale Ceramics payroll", 364_000, "income"),
            event("2026-10-02", "Rent", -160_000, "housing"),
            event("2026-10-05", "Transfer to Rainy Day Savings", -50_000, "internal"),
            event("2026-10-12", "Hale Ceramics payroll", 364_000, "income"),
            event("2026-10-16", "Sent to Avery Chen", -7_500, "transfer"),
            event("2026-10-20", "Kiln & Co supply", -12_640, "shopping"));

    private static final List<Event> JORDAN_SAVINGS = List.of(
            event("2026-08-01", "Opening balance", 960_000, "opening"),
            interest("2026-09-01"),
            interest("2026-10-01"),
            event("2026-10-05", "Transfer from Everyday Checking", 50_000, "internal"));

    private static Event event(String date, String description, long amount, String category) {
        return new Event(date, description, amount, category);
    }

    private static Event interest(String date) {
        return new Event(date, "Savings interest", null, "interest");
    }
}
