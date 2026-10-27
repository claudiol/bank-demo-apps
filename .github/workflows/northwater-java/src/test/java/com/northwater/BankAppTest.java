package com.northwater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.javalin.Javalin;
import java.time.Duration;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BankAppTest {
    private static final Pattern CSRF_FIELD = Pattern.compile("name=\"csrf\" value=\"([^\"]+)\"");
    private static final Pattern NONCE_FIELD = Pattern.compile("name=\"nonce\" value=\"([^\"]+)\"");

    @Test
    void parsesAmounts() {
        assertEquals(2500, Money.parse("25"));
        assertEquals(100_050, Money.parse("$1,000.50"));
        assertEquals(Money.MAX_CENTS, Money.parse("1000000.00"));
        for (String sample : new String[] {"", "0.00", "-5", "10.005", "1000000.01", "abc"}) {
            assertThrows(FormException.class, () -> Money.parse(sample), sample);
        }
    }

    @Test
    void movesMoneyOnlyInsideTheLedger(@TempDir Path temp) throws Exception {
        Path database = temp.resolve("northwater.db");
        Javalin app = BankApp.create(database);
        app.start(0);
        try {
            Ledger ledger = Ledger.open(database);
            Browser browser = new Browser("http://127.0.0.1:" + app.port());

            HttpResponse<String> login = browser.get("/", false);
            assertEquals(200, login.statusCode());
            assertTrue(login.body().contains("Sign in"));
            assertTrue(login.body().contains("Avery Chen"));
            assertTrue(login.body().contains("name=\"csrf\""));

            HttpResponse<String> wrong = browser.post("/login", Map.of(
                    "csrf", csrf(login.body()),
                    "email", "avery@northwater.demo",
                    "password", "nope"), false);
            assertEquals(401, wrong.statusCode());
            assertTrue(wrong.body().contains("incorrect"));

            HttpResponse<String> blocked = browser.get("/transfer", false);
            assertEquals(303, blocked.statusCode());
            assertEquals("/", location(blocked));

            HttpResponse<String> home = browser.signIn("avery@northwater.demo");
            assertTrue(home.body().contains("Available"));
            assertTrue(home.body().contains("Everyday Checking"));
            Ledger.Account averyChecking = ledger.accountFor("avery@northwater.demo", "checking");
            Ledger.Account averySavings = ledger.accountFor("avery@northwater.demo", "savings");
            Ledger.Account jordanChecking = ledger.accountFor("jordan@northwater.demo", "checking");
            long total = averyChecking.balanceCents() + averySavings.balanceCents();
            assertTrue(home.body().contains("data-cents=\"" + total + "\""));
            assertFalse(home.body().contains(jordanChecking.number()));

            HttpResponse<String> stolen = browser.get("/accounts/" + jordanChecking.id(), false);
            assertEquals(404, stolen.statusCode());
            assertFalse(stolen.body().contains(jordanChecking.number()));

            HttpResponse<String> statement = browser.get("/accounts/" + averyChecking.id(), false);
            assertTrue(statement.body().contains(averyChecking.number()));
            assertTrue(statement.body().contains("Harbor Market"));

            HttpResponse<String> moneyOut = browser.get("/accounts/" + averyChecking.id() + "?flow=out", false);
            assertTrue(moneyOut.body().contains("Linden Apartments"));
            assertFalse(moneyOut.body().contains("Northwind Studio payroll"));

            HttpResponse<String> found = browser.get("/accounts/" + averyChecking.id() + "?q=harbor", false);
            assertTrue(found.body().contains("Harbor Market"));
            assertFalse(found.body().contains("Northwind Studio payroll"));
            HttpResponse<String> wildcard = browser.get("/accounts/" + averyChecking.id() + "?q=%25", false);
            assertFalse(wildcard.body().contains("Harbor Market"));

            long beforeChecking = ledger.accountFor("avery@northwater.demo", "checking").balanceCents();
            long beforeSavings = ledger.accountFor("avery@northwater.demo", "savings").balanceCents();
            HttpResponse<String> form = browser.get("/transfer", false);
            HttpResponse<String> moved = browser.post("/transfer", Map.of(
                    "csrf", csrf(form.body()),
                    "from_id", Integer.toString(averyChecking.id()),
                    "destination", "account:" + averySavings.id(),
                    "account_number", "",
                    "amount", "10.25",
                    "memo", "Rainy afternoon"), true);
            assertTrue(moved.body().contains("Move now"));
            assertTrue(moved.body().contains("Rainy afternoon"));
            String nonce = nonce(moved.body());
            HttpResponse<String> receipt = browser.post("/confirm", Map.of(
                    "csrf", csrf(moved.body()),
                    "nonce", nonce), true);
            assertTrue(receipt.body().contains("NW-"));
            assertTrue(receipt.body().contains("Moved $10.25"));
            assertEquals(beforeChecking - 1025, ledger.accountFor("avery@northwater.demo", "checking").balanceCents());
            assertEquals(beforeSavings + 1025, ledger.accountFor("avery@northwater.demo", "savings").balanceCents());

            HttpResponse<String> replay = browser.post("/confirm", Map.of(
                    "csrf", csrf(receipt.body()),
                    "nonce", nonce), false);
            assertEquals(303, replay.statusCode());
            assertEquals(beforeChecking - 1025, ledger.accountFor("avery@northwater.demo", "checking").balanceCents());

            Pending again = new Pending(
                    "replay-nonce-1", "transfer", "own", averyChecking.id(), averySavings.id(), "", 100, "");
            Ledger.Posting first = ledger.post(averyChecking.userId(), again);
            Ledger.Posting second = ledger.post(averyChecking.userId(), again);
            assertEquals(first.confirmation(), second.confirmation());
            assertTrue(second.replayed());
            assertEquals(beforeChecking - 1125, ledger.accountFor("avery@northwater.demo", "checking").balanceCents());

            HttpResponse<String> sameForm = browser.get("/transfer", false);
            HttpResponse<String> same = browser.post("/transfer", Map.of(
                    "csrf", csrf(sameForm.body()),
                    "from_id", Integer.toString(averyChecking.id()),
                    "destination", "account:" + averyChecking.id(),
                    "amount", "1.00",
                    "memo", "",
                    "account_number", ""), false);
            assertEquals(400, same.statusCode());
            assertTrue(same.body().contains("different account"));

            HttpResponse<String> unknownForm = browser.get("/transfer", false);
            HttpResponse<String> unknown = browser.post("/transfer", Map.of(
                    "csrf", csrf(unknownForm.body()),
                    "from_id", Integer.toString(averyChecking.id()),
                    "destination", "external",
                    "account_number", "' OR 1=1 --",
                    "amount", "1.00",
                    "memo", ""), false);
            assertEquals(400, unknown.statusCode());
            assertTrue(unknown.body().contains("No Northwater account") || unknown.body().contains("10-digit"));

            long overCents = ledger.accountFor("avery@northwater.demo", "checking").balanceCents() + 1;
            HttpResponse<String> overForm = browser.get("/transfer", false);
            HttpResponse<String> over = browser.post("/transfer", Map.of(
                    "csrf", csrf(overForm.body()),
                    "from_id", Integer.toString(averyChecking.id()),
                    "destination", "account:" + averySavings.id(),
                    "amount", overCents / 100 + "." + String.format("%02d", overCents % 100),
                    "memo", "",
                    "account_number", ""), false);
            assertEquals(400, over.statusCode());
            assertTrue(over.body().contains("available"));
            assertEquals(beforeChecking - 1125, ledger.accountFor("avery@northwater.demo", "checking").balanceCents());

            long beforeJordan = ledger.accountFor("jordan@northwater.demo", "checking").balanceCents();
            long beforeAvery = ledger.accountFor("avery@northwater.demo", "checking").balanceCents();
            HttpResponse<String> externalForm = browser.get("/transfer", false);
            HttpResponse<String> external = browser.post("/transfer", Map.of(
                    "csrf", csrf(externalForm.body()),
                    "from_id", Integer.toString(averyChecking.id()),
                    "destination", "external",
                    "account_number", jordanChecking.number(),
                    "amount", "15.00",
                    "memo", "<script>alert(1)</script>"), true);
            assertTrue(external.body().contains(jordanChecking.number()));
            HttpResponse<String> sent = browser.post("/confirm", Map.of(
                    "csrf", csrf(external.body()),
                    "nonce", nonce(external.body())), true);
            assertTrue(sent.body().contains("Sent $15.00"));
            assertFalse(sent.body().contains("<script>alert(1)</script>"));
            assertTrue(sent.body().contains("&lt;script&gt;alert(1)&lt;/script&gt;"));
            assertEquals(beforeAvery - 1500, ledger.accountFor("avery@northwater.demo", "checking").balanceCents());
            assertEquals(beforeJordan + 1500, ledger.accountFor("jordan@northwater.demo", "checking").balanceCents());

            HttpResponse<String> payForm = browser.get("/pay", false);
            HttpResponse<String> payReview = browser.post("/pay", Map.of(
                    "csrf", csrf(payForm.body()),
                    "from_id", Integer.toString(averyChecking.id()),
                    "payee", "Corner Bookstore",
                    "amount", "12.40",
                    "memo", "October"), true);
            assertTrue(payReview.body().contains("Pay now"));
            assertTrue(payReview.body().contains("Corner Bookstore"));
            HttpResponse<String> paid = browser.post("/confirm", Map.of(
                    "csrf", csrf(payReview.body()),
                    "nonce", nonce(payReview.body())), true);
            assertTrue(paid.body().contains("Paid $12.40"));
            assertEquals(beforeAvery - 1500 - 1240, ledger.accountFor("avery@northwater.demo", "checking").balanceCents());
            assertEquals(beforeJordan + 1500, ledger.accountFor("jordan@northwater.demo", "checking").balanceCents());
            ledger.assertBalanced();

            HttpResponse<String> signedOut = browser.post("/logout", Map.of("csrf", csrf(paid.body())), true);
            assertTrue(signedOut.body().contains("Sign in"));
            assertTrue(signedOut.body().contains("Signed out."));
            HttpResponse<String> once = browser.get("/", false);
            assertFalse(once.body().contains("Signed out."));

            HttpResponse<String> jordanHome = browser.signIn("jordan@northwater.demo");
            assertTrue(jordanHome.body().contains("Received from Avery Chen"));
            assertFalse(jordanHome.body().contains(averyChecking.number()));
        } finally {
            app.stop();
        }
    }

    private static String csrf(String html) {
        return group(CSRF_FIELD, html, "csrf token missing");
    }

    private static String nonce(String html) {
        return group(NONCE_FIELD, html, "nonce missing");
    }

    private static String group(Pattern pattern, String html, String message) {
        Matcher matcher = pattern.matcher(html);
        assertTrue(matcher.find(), message);
        return matcher.group(1);
    }

    private static String location(HttpResponse<?> response) {
        String value = response.headers().firstValue("location").orElse("");
        if (value.startsWith("http://") || value.startsWith("https://")) {
            return URI.create(value).getRawPath();
        }
        int query = value.indexOf('?');
        return query < 0 ? value : value.substring(0, query);
    }

    private static final class Browser {
        private final String base;
        private final HttpClient client;

        private Browser(String base) {
            this.base = base;
            CookieManager cookies = new CookieManager();
            cookies.setCookiePolicy(CookiePolicy.ACCEPT_ALL);
            this.client = HttpClient.newBuilder()
                    .cookieHandler(cookies)
                    .connectTimeout(Duration.ofSeconds(5))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
        }

        private HttpResponse<String> signIn(String email) throws Exception {
            HttpResponse<String> page = get("/", false);
            return post("/login", Map.of(
                    "csrf", csrf(page.body()),
                    "email", email,
                    "password", SeedData.DEMO_PASSWORD), true);
        }

        private HttpResponse<String> get(String path, boolean follow) throws Exception {
            HttpRequest request = HttpRequest.newBuilder(URI.create(base + path))
                    .timeout(Duration.ofSeconds(20))
                    .GET()
                    .build();
            return send(request, follow);
        }

        private HttpResponse<String> post(String path, Map<String, String> form, boolean follow) throws Exception {
            HttpRequest request = HttpRequest.newBuilder(URI.create(base + path))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(encode(form)))
                    .build();
            return send(request, follow);
        }

        private HttpResponse<String> send(HttpRequest request, boolean follow) throws Exception {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            int hops = 0;
            while (follow && response.statusCode() == 303 && hops < 5) {
                String next = response.headers().firstValue("location").orElseThrow();
                if (next.startsWith("/")) {
                    next = base + next;
                }
                response = client.send(
                        HttpRequest.newBuilder(URI.create(next)).timeout(Duration.ofSeconds(20)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                hops++;
            }
            return response;
        }

        private static String encode(Map<String, String> form) {
            StringBuilder body = new StringBuilder();
            for (Map.Entry<String, String> entry : new LinkedHashMap<>(form).entrySet()) {
                if (!body.isEmpty()) {
                    body.append('&');
                }
                body.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8));
                body.append('=');
                body.append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
            }
            return body.toString();
        }
    }
}
