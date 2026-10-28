package com.northwater;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.staticfiles.Location;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.eclipse.jetty.ee10.servlet.SessionHandler;
import org.eclipse.jetty.http.HttpCookie;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Local demonstration bank. Money in this process never leaves the machine. */
public final class BankApp {
    private static final Logger log = LoggerFactory.getLogger(BankApp.class);
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM", Locale.US);
    private static final List<String> PAYEES = List.of(
            "Linden Apartments", "City Power & Light", "Harbor Market", "Metro Transit");
    private static final Map<String, String> CATEGORIES = Map.ofEntries(
            Map.entry("opening", "Opening"),
            Map.entry("income", "Income"),
            Map.entry("groceries", "Groceries"),
            Map.entry("housing", "Housing"),
            Map.entry("utilities", "Utilities"),
            Map.entry("transport", "Transport"),
            Map.entry("transfer", "Transfer"),
            Map.entry("internal", "Transfer"),
            Map.entry("shopping", "Shopping"),
            Map.entry("health", "Health"),
            Map.entry("dining", "Dining"),
            Map.entry("adjustment", "Adjustment"),
            Map.entry("interest", "Interest"),
            Map.entry("bill", "Bill"));
    private static final String CSP = """
            default-src 'self'; \
            style-src 'self'; \
            font-src 'self'; \
            img-src 'self'; \
            script-src 'self'; \
            form-action 'self'; \
            base-uri 'self'; \
            frame-ancestors 'none'\
            """;

    private final Ledger ledger;
    private final Pages pages;

    private BankApp(Ledger ledger, Pages pages) {
        this.ledger = ledger;
        this.pages = pages;
    }

    public static Javalin create(Path database) {
        Ledger ledger = Ledger.open(database);
        return new BankApp(ledger, new Pages(ledger)).javalin();
    }

    private Javalin javalin() {
        return Javalin.create(config -> {
            config.startup.showJavalinBanner = false;
            config.staticFiles.add(staticFiles -> {
                staticFiles.hostedPath = "/static";
                staticFiles.directory = "/static";
                staticFiles.location = Location.CLASSPATH;
            });
            config.jetty.modifyServletContextHandler(handler -> handler.setSessionHandler(sessions()));
            var routes = config.routes;
            routes.before(ctx -> ctx.header("X-Content-Type-Options", "nosniff"));
            routes.after(ctx -> {
                ctx.header("X-Frame-Options", "DENY");
                ctx.header("Referrer-Policy", "same-origin");
                ctx.header("Cross-Origin-Opener-Policy", "same-origin");
                ctx.header("X-Permitted-Cross-Domain-Policies", "none");
                ctx.header("Content-Security-Policy", CSP);
                String type = ctx.res().getContentType();
                if (type != null && type.startsWith("text/html")) {
                    ctx.header("Cache-Control", "no-store");
                }
            });
            routes.get("/favicon.ico", this::favicon);
            routes.get("/", this::home);
            routes.post("/login", this::login);
            routes.post("/logout", this::logout);
            routes.get("/accounts/{accountId}", this::account);
            routes.get("/transfer", this::transferForm);
            routes.post("/transfer", this::transferSubmit);
            routes.get("/pay", this::payForm);
            routes.post("/pay", this::paySubmit);
            routes.get("/review", this::review);
            routes.post("/confirm", this::confirm);
            routes.get("/receipts/{confirmation}", this::receipt);
            routes.error(404, ctx -> {
                if (Boolean.TRUE.equals(ctx.attribute("northwater.handled"))) {
                    return;
                }
                missing(ctx, "That page isn't on the books.", "The link may be old, or the page isn't part of this demo.");
            });
            routes.exception(Exception.class, (exception, ctx) -> {
                log.warn("request failed", exception);
                if (!ctx.res().isCommitted()) {
                    ctx.status(500);
                    ctx.contentType("text/plain; charset=UTF-8");
                    ctx.result("Something went wrong.");
                }
            });
        });
    }

    private static SessionHandler sessions() {
        SessionHandler sessions = new SessionHandler();
        sessions.setHttpOnly(true);
        sessions.setSameSite(HttpCookie.SameSite.LAX);
        sessions.setMaxInactiveInterval(8 * 60 * 60);
        var cookie = sessions.getSessionCookieConfig();
        cookie.setName("northwater");
        cookie.setHttpOnly(true);
        cookie.setPath("/");
        cookie.setMaxAge(8 * 60 * 60);
        return sessions;
    }

    private void favicon(Context ctx) throws Exception {
        try (InputStream in = BankApp.class.getResourceAsStream("/static/favicon.svg")) {
            if (in == null) {
                ctx.status(404);
                return;
            }
            ctx.contentType("image/svg+xml");
            ctx.result(in.readAllBytes());
        }
    }

    private void home(Context ctx) {
        Ledger.User user = pages.currentUser(ctx);
        if (user == null) {
            html(ctx, "login", loginModel(null, ""), 200);
            return;
        }
        LocalDate today = LocalDate.now();
        List<Ledger.Account> accounts = ledger.accounts(user.id());
        long total = 0;
        for (Ledger.Account account : accounts) {
            total += account.balanceCents();
        }
        long[] month = ledger.monthTotals(user.id(), today, null, true);
        Map<String, Object> model = new HashMap<>();
        model.put("active", "overview");
        model.put("greeting", Dates.greeting(LocalTime.now()));
        model.put("todayLong", Dates.longDate(today));
        model.put("totalCents", total);
        model.put("totalDollars", Money.dollars(total));
        model.put("centsPart", Money.centsPart(total));
        model.put("summary", "Across " + accounts.size() + " accounts. " + activity(month[0], month[1], today, true));
        model.put("accounts", accounts.stream().map(BankApp::accountView).toList());
        model.put("transactions", ledger.recent(user.id()).stream().map(tx -> txView(tx, today)).toList());
        ledgerFlags(model, true, false, false, true, "Recent activity");
        html(ctx, "dashboard", model, 200);
    }

    private void login(Context ctx) {
        String email = form(ctx, "email");
        String password = form(ctx, "password");
        if (!csrfOk(ctx)) {
            String shown = clip(email, 120);
            html(ctx, "login", loginModel("The form expired. Sign in again.", shown), 403);
            return;
        }
        String address = email.strip().toLowerCase(Locale.ROOT);
        Ledger.User user = ledger.authenticate(address, password);
        if (user == null) {
            html(ctx, "login", loginModel("Email or password is incorrect.", clip(address, 120)), 401);
            return;
        }
        signIn(ctx, user);
        seeOther(ctx, "/");
    }

    private void logout(Context ctx) {
        if (!csrfOk(ctx)) {
            reject(ctx, "The form expired. Try signing out again.");
            return;
        }
        var session = ctx.req().getSession(false);
        if (session != null) {
            session.invalidate();
        }
        ctx.sessionAttribute("flash", new Views.Flash("ok", "Signed out."));
        seeOther(ctx, "/");
    }

    private void account(Context ctx) {
        Ledger.User user = pages.currentUser(ctx);
        if (user == null) {
            seeOther(ctx, "/");
            return;
        }
        Integer accountId = pathInt(ctx.pathParam("accountId"));
        Ledger.Account account = accountId == null ? null : ledger.owned(user.id(), accountId);
        if (account == null) {
            missing(ctx, "That page isn't on the books.", "The link may be old, or the account isn't yours.");
            return;
        }
        String flow = flow(ctx.queryParam("flow"));
        String query = searchQuery(ctx.queryParam("q"));
        LocalDate today = LocalDate.now();
        long[] month = ledger.monthTotals(user.id(), today, account.id(), false);
        List<Views.Filter> filters = List.of(
                filter(account.id(), "all", "All", flow, query),
                filter(account.id(), "in", "Money in", flow, query),
                filter(account.id(), "out", "Money out", flow, query));
        Map<String, Object> model = new HashMap<>();
        model.put("active", "overview");
        model.put("account", accountView(account));
        model.put("summary", activity(month[0], month[1], today, false));
        model.put("transactions", ledger.statement(account.id(), flow, query).stream().map(tx -> txView(tx, today)).toList());
        model.put("filters", filters);
        model.put("flow", flow);
        model.put("q", query);
        ledgerFlags(model, false, true, true, false, "Statement for " + account.nickname());
        html(ctx, "account", model, 200);
    }

    private void transferForm(Context ctx) {
        clearPending(ctx);
        Ledger.User user = pages.currentUser(ctx);
        if (user == null) {
            seeOther(ctx, "/");
            return;
        }
        List<Ledger.Account> accounts = ledger.accounts(user.id());
        int fromId = chosen(accounts, ctx.queryParam("from"));
        html(ctx, "transfer", transferModel(accounts, fromId, defaultDestination(accounts, fromId), "", "", "", null), 200);
    }

    private void transferSubmit(Context ctx) {
        Ledger.User user = pages.currentUser(ctx);
        if (user == null) {
            seeOther(ctx, "/");
            return;
        }
        if (!csrfOk(ctx)) {
            reject(ctx, "The form expired. Start the transfer again.");
            return;
        }
        List<Ledger.Account> accounts = ledger.accounts(user.id());
        String destination = form(ctx, "destination");
        if (!destination.startsWith("account:") && !"external".equals(destination)) {
            destination = "external";
        }
        int fromId = postedFrom(accounts, form(ctx, "from_id"));
        try {
            Pending pending = ledger.prepareTransfer(
                    user.id(),
                    form(ctx, "from_id"),
                    form(ctx, "destination"),
                    form(ctx, "account_number"),
                    form(ctx, "amount"),
                    form(ctx, "memo"));
            ctx.sessionAttribute("pending", pending);
            seeOther(ctx, "/review");
        } catch (FormException ex) {
            clearPending(ctx);
            html(ctx, "transfer", transferModel(
                    accounts,
                    fromId,
                    destination,
                    clip(form(ctx, "account_number"), 20),
                    clip(form(ctx, "amount"), 20),
                    clip(form(ctx, "memo"), 80),
                    ex.getMessage()), 400);
        }
    }

    private void payForm(Context ctx) {
        clearPending(ctx);
        Ledger.User user = pages.currentUser(ctx);
        if (user == null) {
            seeOther(ctx, "/");
            return;
        }
        List<Ledger.Account> accounts = ledger.accounts(user.id());
        html(ctx, "pay", payModel(accounts, chosen(accounts, ctx.queryParam("from")), "", "", "", null), 200);
    }

    private void paySubmit(Context ctx) {
        Ledger.User user = pages.currentUser(ctx);
        if (user == null) {
            seeOther(ctx, "/");
            return;
        }
        if (!csrfOk(ctx)) {
            reject(ctx, "The form expired. Start the payment again.");
            return;
        }
        List<Ledger.Account> accounts = ledger.accounts(user.id());
        int fromId = postedFrom(accounts, form(ctx, "from_id"));
        try {
            Pending pending = ledger.prepareBill(
                    user.id(), form(ctx, "from_id"), form(ctx, "payee"), form(ctx, "amount"), form(ctx, "memo"));
            ctx.sessionAttribute("pending", pending);
            seeOther(ctx, "/review");
        } catch (FormException ex) {
            clearPending(ctx);
            html(ctx, "pay", payModel(
                    accounts,
                    fromId,
                    clip(form(ctx, "payee"), 60),
                    clip(form(ctx, "amount"), 20),
                    clip(form(ctx, "memo"), 80),
                    ex.getMessage()), 400);
        }
    }

    private void review(Context ctx) {
        Ledger.User user = pages.currentUser(ctx);
        if (user == null) {
            seeOther(ctx, "/");
            return;
        }
        Pending pending = pending(ctx);
        if (!Ledger.acceptable(pending)) {
            seeOther(ctx, "/transfer");
            return;
        }
        try {
            Views.Review described = describe(user.id(), pending);
            Map<String, Object> model = new HashMap<>();
            model.put("active", "bill".equals(pending.kind) ? "pay" : "transfer");
            model.put("review", described);
            html(ctx, "review", model, 200);
        } catch (FormException ex) {
            clearPending(ctx);
            ctx.sessionAttribute("flash", new Views.Flash("error", ex.getMessage()));
            seeOther(ctx, "bill".equals(pending.kind) ? "/pay" : "/transfer");
        }
    }

    private void confirm(Context ctx) {
        Ledger.User user = pages.currentUser(ctx);
        if (user == null) {
            seeOther(ctx, "/");
            return;
        }
        if (!csrfOk(ctx)) {
            reject(ctx, "The form expired. Review the transfer again.");
            return;
        }
        Pending pending = pending(ctx);
        if (!Ledger.acceptable(pending) || !constantEquals(pending.nonce, form(ctx, "nonce"))) {
            ctx.sessionAttribute("flash", new Views.Flash("error", "That transfer is no longer available. Start again."));
            seeOther(ctx, "/transfer");
            return;
        }
        try {
            Ledger.Posting posting = ledger.post(user.id(), pending);
            clearPending(ctx);
            ctx.sessionAttribute("flash", new Views.Flash("ok", posting.message()));
            seeOther(ctx, "/receipts/" + posting.confirmation());
        } catch (FormException ex) {
            String kind = pending.kind;
            clearPending(ctx);
            ctx.sessionAttribute("flash", new Views.Flash("error", ex.getMessage()));
            seeOther(ctx, "bill".equals(kind) ? "/pay" : "/transfer");
        }
    }

    private void receipt(Context ctx) {
        Ledger.User user = pages.currentUser(ctx);
        if (user == null) {
            seeOther(ctx, "/");
            return;
        }
        Ledger.ReceiptRow row = ledger.receipt(user.id(), ctx.pathParam("confirmation"));
        if (row == null) {
            missing(ctx, "That page isn't on the books.", "The receipt isn't available on this customer.");
            return;
        }
        Views.Receipt receipt = receiptView(row);
        Map<String, Object> model = new HashMap<>();
        model.put("active", "bill".equals(receipt.kind) ? "pay" : "transfer");
        model.put("receipt", receipt);
        html(ctx, "receipt", model, 200);
    }

    private Views.Review describe(int userId, Pending pending) {
        if (!Ledger.acceptable(pending)) {
            throw new FormException("That transfer is no longer available. Start again.");
        }
        Ledger.Account source = ledger.owned(userId, pending.fromId);
        if (source == null) {
            throw new FormException("Choose an account of yours.");
        }
        long amount = pending.amountCents;
        if (amount <= 0 || amount > Money.MAX_CENTS) {
            throw new FormException("Enter a dollar amount like 25.00.");
        }
        if (amount > source.balanceCents()) {
            throw new FormException(source.nickname() + " has " + Money.format(source.balanceCents()) + " available.");
        }
        Ledger.Account dest = null;
        String toLine;
        String headline;
        String confirmLabel;
        String editHref;
        String eyebrow;
        if ("own".equals(pending.scope) || "member".equals(pending.scope)) {
            dest = ledger.findAny(pending.toId);
            if (dest == null || dest.id() == source.id()) {
                throw new FormException("Choose where the money should go.");
            }
            if ("own".equals(pending.scope) && dest.userId() != userId) {
                throw new FormException("Choose one of your accounts.");
            }
            if ("member".equals(pending.scope) && dest.userId() == userId) {
                throw new FormException("That account is yours. Choose it from the list instead.");
            }
        } else if (!"bill".equals(pending.scope) || pending.payee.isBlank()) {
            throw new FormException("That transfer is no longer available. Start again.");
        }
        if ("own".equals(pending.scope)) {
            toLine = dest.nickname() + " · " + Money.mask(dest.number());
            headline = "Move " + Money.format(amount);
            confirmLabel = "Move now";
            editHref = "/transfer";
            eyebrow = "Review transfer";
        } else if ("member".equals(pending.scope)) {
            toLine = dest.ownerName() + ", " + dest.nickname() + " · " + dest.number();
            headline = "Send " + Money.format(amount);
            confirmLabel = "Send now";
            editHref = "/transfer";
            eyebrow = "Review transfer";
        } else {
            toLine = pending.payee;
            headline = "Pay " + Money.format(amount);
            confirmLabel = "Pay now";
            editHref = "/pay";
            eyebrow = "Review bill";
        }
        Views.Review review = new Views.Review();
        review.eyebrow = eyebrow;
        review.headline = headline;
        review.nonce = pending.nonce;
        review.confirmLabel = confirmLabel;
        review.editHref = editHref;
        review.note = "This posts immediately in the demonstration ledger. Nothing leaves this computer.";
        review.lines = List.of(
                new Views.Line("From", source.nickname() + " · " + Money.mask(source.number())),
                new Views.Line("To", toLine),
                new Views.Line("Memo", blank(pending.memo)),
                new Views.Line("Afterward", Money.format(source.balanceCents() - amount) + " left in " + source.nickname()),
                new Views.Line("Timing", "Posts immediately"));
        return review;
    }

    private static Views.Receipt receiptView(Ledger.ReceiptRow row) {
        Views.Receipt receipt = new Views.Receipt();
        receipt.kind = row.kind();
        receipt.confirmation = row.confirmation();
        receipt.fromId = row.fromId();
        String toLine;
        if ("own".equals(row.scope())) {
            toLine = row.toNickname() + " · " + Money.mask(row.toNumber());
            receipt.headline = "Moved " + Money.format(row.amountCents());
        } else if ("member".equals(row.scope())) {
            toLine = row.toName() + ", " + row.toNickname() + " · " + Money.mask(row.toNumber());
            receipt.headline = "Sent " + Money.format(row.amountCents());
        } else {
            toLine = row.payee();
            receipt.headline = "Paid " + Money.format(row.amountCents());
        }
        receipt.lines = List.of(
                new Views.Line("From", row.fromNickname() + " · " + Money.mask(row.fromNumber())),
                new Views.Line("To", toLine),
                new Views.Line("Amount", Money.format(row.amountCents())),
                new Views.Line("Memo", blank(row.memo())),
                new Views.Line("Posted", Dates.timestamp(row.createdAt())));
        return receipt;
    }

    private Map<String, Object> loginModel(String error, String email) {
        Map<String, Object> model = new HashMap<>();
        model.put("error", error);
        model.put("email", email);
        model.put("demoPassword", SeedData.DEMO_PASSWORD);
        model.put("demoPeople", List.of(
                new Views.Person(SeedData.AVERY.fullName(), SeedData.AVERY.email()),
                new Views.Person(SeedData.JORDAN.fullName(), SeedData.JORDAN.email())));
        return model;
    }

    private static Map<String, Object> transferModel(
            List<Ledger.Account> accounts,
            int fromId,
            String destination,
            String accountNumber,
            String amount,
            String memo,
            String error) {
        Map<String, Object> model = new HashMap<>();
        model.put("active", "transfer");
        model.put("accounts", accounts.stream().map(BankApp::accountView).toList());
        model.put("fromId", fromId);
        model.put("destination", destination);
        model.put("accountNumber", accountNumber);
        model.put("amount", amount);
        model.put("memo", memo);
        model.put("error", error);
        return model;
    }

    private static Map<String, Object> payModel(
            List<Ledger.Account> accounts, int fromId, String payee, String amount, String memo, String error) {
        Map<String, Object> model = new HashMap<>();
        model.put("active", "pay");
        model.put("accounts", accounts.stream().map(BankApp::accountView).toList());
        model.put("fromId", fromId);
        model.put("payee", payee);
        model.put("payeeNames", PAYEES);
        model.put("amount", amount);
        model.put("memo", memo);
        model.put("error", error);
        return model;
    }

    private void missing(Context ctx, String heading, String message) {
        ctx.attribute("northwater.handled", Boolean.TRUE);
        Map<String, Object> model = new HashMap<>();
        model.put("pageTitle", "Not found · Northwater");
        model.put("heading", heading);
        model.put("message", message);
        html(ctx, "notice", model, 404);
    }

    private void reject(Context ctx, String message) {
        Map<String, Object> model = new HashMap<>();
        model.put("pageTitle", "Unable to continue · Northwater");
        model.put("heading", "That didn't go through");
        model.put("message", message);
        html(ctx, "notice", model, 403);
    }

    private void html(Context ctx, String template, Map<String, Object> model, int status) {
        ctx.contentType("text/html; charset=UTF-8");
        ctx.result(pages.render(ctx, template, model));
        ctx.status(status);
    }

    private static void seeOther(Context ctx, String location) {
        ctx.status(303);
        ctx.header("Location", location);
        ctx.result("Redirecting");
    }

    private void signIn(Context ctx, Ledger.User user) {
        var existing = ctx.req().getSession(false);
        if (existing != null) {
            existing.invalidate();
        }
        ctx.req().getSession(true);
        ctx.sessionAttribute("userId", user.id());
        ctx.sessionAttribute("csrf", Pages.newToken());
    }

    private static void clearPending(Context ctx) {
        var session = ctx.req().getSession(false);
        if (session != null) {
            session.removeAttribute("pending");
        }
    }

    private static Pending pending(Context ctx) {
        Object raw = ctx.sessionAttribute("pending");
        return raw instanceof Pending value ? value : null;
    }

    private static boolean csrfOk(Context ctx) {
        Object expected = ctx.sessionAttribute("csrf");
        if (!(expected instanceof String token)) {
            return false;
        }
        return constantEquals(token, form(ctx, "csrf"));
    }

    private static boolean constantEquals(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    private static Views.AccountView accountView(Ledger.Account account) {
        Views.AccountView view = new Views.AccountView();
        view.id = account.id();
        view.nickname = account.nickname();
        view.kindLabel = "checking".equals(account.kind()) ? "Checking" : "Savings";
        view.mask = Money.mask(account.number());
        view.number = account.number();
        view.routing = account.routing();
        view.balance = Money.format(account.balanceCents());
        view.balanceCents = account.balanceCents();
        view.dollars = Money.dollars(account.balanceCents());
        view.centsPart = Money.centsPart(account.balanceCents());
        view.opened = Dates.monthDayYear(account.openedOn());
        view.apy = "savings".equals(account.kind()) ? "3.80% APY" : "";
        return view;
    }

    private static Views.TxView txView(Ledger.Tx tx, LocalDate today) {
        Views.TxView view = new Views.TxView();
        view.accountId = tx.accountId();
        view.posted = Dates.pretty(tx.postedAt(), today);
        view.description = tx.description();
        view.memo = tx.memo() == null ? "" : tx.memo();
        view.category = CATEGORIES.getOrDefault(tx.category(), tx.category());
        view.amount = Money.format(tx.amountCents(), true);
        view.direction = tx.amountCents() > 0 ? "in" : "out";
        view.balance = Money.format(tx.balanceAfter());
        view.account = tx.number() == null || tx.number().isEmpty()
                ? ""
                : tx.nickname() + " · " + Money.mask(tx.number());
        return view;
    }

    private static void ledgerFlags(
            Map<String, Object> model,
            boolean showAccount,
            boolean showCategory,
            boolean showBalance,
            boolean linkDescription,
            String caption) {
        model.put("showAccount", showAccount);
        model.put("showCategory", showCategory);
        model.put("showBalance", showBalance);
        model.put("linkDescription", linkDescription);
        model.put("caption", caption);
    }

    private static Views.Filter filter(int accountId, String flow, String label, String current, String query) {
        Views.Filter filter = new Views.Filter();
        String href = "/accounts/" + accountId + "?flow=" + flow;
        if (!query.isEmpty()) {
            href += "&q=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
        }
        filter.href = href;
        filter.label = label;
        filter.current = flow.equals(current);
        return filter;
    }

    private static String activity(long incoming, long outgoing, LocalDate today, boolean skipInternal) {
        String month = today.format(MONTH);
        String sentence = incoming == 0 && outgoing == 0
                ? "Nothing posted in " + month
                : Money.format(incoming) + " in and " + Money.format(outgoing) + " out in " + month;
        if (skipInternal) {
            sentence += ", not counting transfers between your own accounts";
        }
        return sentence + ".";
    }

    private static int chosen(List<Ledger.Account> accounts, String requested) {
        if (requested != null && !requested.isEmpty() && requested.chars().allMatch(Character::isDigit) && requested.length() < 10) {
            int id = Integer.parseInt(requested);
            for (Ledger.Account account : accounts) {
                if (account.id() == id) {
                    return id;
                }
            }
        }
        for (Ledger.Account account : accounts) {
            if ("checking".equals(account.kind())) {
                return account.id();
            }
        }
        return accounts.get(0).id();
    }

    private static String defaultDestination(List<Ledger.Account> accounts, int fromId) {
        for (Ledger.Account account : accounts) {
            if (account.id() != fromId) {
                return "account:" + account.id();
            }
        }
        return "external";
    }

    private static int postedFrom(List<Ledger.Account> accounts, String raw) {
        if (raw != null && !raw.isEmpty() && raw.chars().allMatch(Character::isDigit) && raw.length() < 10) {
            return Integer.parseInt(raw);
        }
        return chosen(accounts, null);
    }

    private static Integer pathInt(String raw) {
        if (raw == null || raw.isEmpty() || !raw.chars().allMatch(Character::isDigit) || raw.length() > 9) {
            return null;
        }
        return Integer.parseInt(raw);
    }

    private static String flow(String raw) {
        if ("in".equals(raw) || "out".equals(raw)) {
            return raw;
        }
        return "all";
    }

    private static String searchQuery(String raw) {
        if (raw == null) {
            return "";
        }
        String query = raw.strip();
        if (query.length() > 80) {
            query = query.substring(0, 80);
        }
        for (int i = 0; i < query.length(); i++) {
            char ch = query.charAt(i);
            if (ch < 32 || ch == 127) {
                return "";
            }
        }
        return query;
    }

    private static String form(Context ctx, String name) {
        String value = ctx.formParam(name);
        return value == null ? "" : value;
    }

    private static String clip(String raw, int limit) {
        if (raw == null) {
            return "";
        }
        return raw.length() <= limit ? raw : raw.substring(0, limit);
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? "\u2014" : value;
    }
}
