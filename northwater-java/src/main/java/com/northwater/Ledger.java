package com.northwater;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** SQLite ledger. Amounts are integer cents. Nothing here is sent to a bank. */
public final class Ledger {
    public static final String SEED_VERSION = "1";
    public static final String ROUTING = "260000000";
    private static final int PBKDF2_ROUNDS = 100_000;
    private static final String DUMMY_HASH = hashPassword("not-a-real-password", new byte[16]);

    private final Path path;
    private final SecureRandom random = new SecureRandom();

    private Ledger(Path path) {
        this.path = path;
    }

    public static Ledger open(Path database) {
        try {
            Path parent = database.getParent();
            Files.createDirectories(parent == null ? Path.of(".") : parent);
        } catch (IOException ex) {
            throw new IllegalStateException("Could not create the ledger folder", ex);
        }
        Ledger ledger = new Ledger(database);
        ledger.init();
        return ledger;
    }

    public record User(int id, String email, String fullName, String city, String memberSince) {}

    public record Account(
            int id,
            int userId,
            String nickname,
            String kind,
            String number,
            String routing,
            long balanceCents,
            String openedOn,
            String ownerName) {}

    public record Tx(
            int accountId,
            String postedAt,
            String description,
            String memo,
            long amountCents,
            long balanceAfter,
            String category,
            String nickname,
            String number) {}

    public record Posting(String confirmation, String message, boolean replayed) {}

    public User findUser(int id) {
        try (Connection conn = connect();
                PreparedStatement ps = conn.prepareStatement(
                        "SELECT id, email, full_name, city, member_since FROM users WHERE id = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readUser(rs) : null;
            }
        } catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public User authenticate(String email, String password) {
        String address = email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
        String candidate = password != null && password.length() <= 128 ? password : "";
        User user = null;
        String stored = DUMMY_HASH;
        try (Connection conn = connect();
                PreparedStatement ps = conn.prepareStatement(
                        "SELECT id, email, full_name, city, member_since, password_hash FROM users WHERE email = ?")) {
            ps.setString(1, address);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    user = readUser(rs);
                    stored = rs.getString("password_hash");
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
        boolean passwordOk = verifyPassword(candidate.isEmpty() ? "missing" : candidate, stored);
        if (user == null || candidate.isEmpty() || !passwordOk) {
            return null;
        }
        return user;
    }

    public List<Account> accounts(int userId) {
        String sql = """
                SELECT a.id, a.user_id, a.nickname, a.kind, a.account_number, a.routing_number,
                       a.balance_cents, a.opened_on, u.full_name AS owner_name
                FROM accounts a
                JOIN users u ON u.id = a.user_id
                WHERE a.user_id = ?
                ORDER BY CASE a.kind WHEN 'checking' THEN 0 ELSE 1 END, a.id
                """;
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                List<Account> accounts = new ArrayList<>();
                while (rs.next()) {
                    accounts.add(readAccount(rs));
                }
                return accounts;
            }
        } catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public Account owned(int userId, int accountId) {
        for (Account account : accounts(userId)) {
            if (account.id() == accountId) {
                return account;
            }
        }
        return null;
    }

    public Account findNumber(String number) {
        String sql = """
                SELECT a.id, a.user_id, a.nickname, a.kind, a.account_number, a.routing_number,
                       a.balance_cents, a.opened_on, u.full_name AS owner_name
                FROM accounts a
                JOIN users u ON u.id = a.user_id
                WHERE a.account_number = ?
                """;
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, number);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readAccount(rs) : null;
            }
        } catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public List<Tx> recent(int userId) {
        String sql = """
                SELECT t.account_id, t.posted_at, t.description, t.memo, t.amount_cents, t.balance_after_cents,
                       t.category, a.nickname, a.account_number
                FROM transactions t
                JOIN accounts a ON a.id = t.account_id
                WHERE a.user_id = ?
                ORDER BY t.posted_at DESC, t.id DESC
                LIMIT 8
                """;
        return queryTx(sql, userId);
    }

    public List<Tx> statement(int accountId, String flow, String query) {
        StringBuilder sql = new StringBuilder("""
                SELECT t.account_id, t.posted_at, t.description, t.memo, t.amount_cents, t.balance_after_cents,
                       t.category, '' AS nickname, '' AS account_number
                FROM transactions t
                WHERE t.account_id = ?
                """);
        List<Object> params = new ArrayList<>();
        params.add(accountId);
        if ("in".equals(flow)) {
            sql.append(" AND t.amount_cents > 0 AND t.category != 'opening'");
        } else if ("out".equals(flow)) {
            sql.append(" AND t.amount_cents < 0");
        }
        if (query != null && !query.isEmpty()) {
            sql.append(" AND (t.description LIKE ? ESCAPE '\\' OR IFNULL(t.memo, '') LIKE ? ESCAPE '\\')");
            String pattern = like(query);
            params.add(pattern);
            params.add(pattern);
        }
        sql.append(" ORDER BY t.posted_at DESC, t.id DESC");
        return queryTx(sql.toString(), params.toArray());
    }

    public long[] monthTotals(int userId, LocalDate today, Integer accountId, boolean skipInternal) {
        String start = today.withDayOfMonth(1).toString();
        String sql = accountId == null
                ? """
                SELECT t.amount_cents, t.category
                FROM transactions t
                JOIN accounts a ON a.id = t.account_id
                WHERE a.user_id = ? AND t.posted_at >= ?
                """
                : """
                SELECT amount_cents, category
                FROM transactions
                WHERE account_id = ? AND posted_at >= ?
                """;
        long incoming = 0;
        long outgoing = 0;
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            if (accountId == null) {
                ps.setInt(1, userId);
            } else {
                ps.setInt(1, accountId);
            }
            ps.setString(2, start);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String category = rs.getString(2);
                    if ("opening".equals(category) || (skipInternal && "internal".equals(category))) {
                        continue;
                    }
                    long amount = rs.getLong(1);
                    if (amount > 0) {
                        incoming += amount;
                    } else if (amount < 0) {
                        outgoing -= amount;
                    }
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
        return new long[] {incoming, outgoing};
    }

    public Pending prepareTransfer(
            int userId, String fromRaw, String destination, String accountNumber, String amountRaw, String memoRaw) {
        Account source = requireOwned(userId, fromRaw);
        String memo = clean(memoRaw, 80);
        long amount = Money.parse(amountRaw);
        if (amount > source.balanceCents()) {
            throw new FormException(source.nickname() + " has " + Money.format(source.balanceCents()) + " available.");
        }
        Integer toId;
        String scope;
        if (destination != null && destination.startsWith("account:")) {
            Account dest = requireOwned(userId, destination.substring("account:".length()));
            if (dest.id() == source.id()) {
                throw new FormException("Choose a different account.");
            }
            toId = dest.id();
            scope = "own";
        } else if ("external".equals(destination)) {
            String number = normalizeNumber(accountNumber);
            Account dest = findNumber(number);
            if (dest == null) {
                throw new FormException("No Northwater account uses that number.");
            }
            if (dest.userId() == userId) {
                throw new FormException("That account is yours. Choose it from the list instead.");
            }
            toId = dest.id();
            scope = "member";
        } else {
            throw new FormException("Choose where the money should go.");
        }
        return new Pending(nonce(), "transfer", scope, source.id(), toId, "", amount, memo);
    }

    public Pending prepareBill(int userId, String fromRaw, String payeeRaw, String amountRaw, String memoRaw) {
        Account source = requireOwned(userId, fromRaw);
        String payee = clean(payeeRaw, 60);
        if (payee.isEmpty()) {
            throw new FormException("Enter a payee.");
        }
        String memo = clean(memoRaw, 80);
        long amount = Money.parse(amountRaw);
        if (amount > source.balanceCents()) {
            throw new FormException(source.nickname() + " has " + Money.format(source.balanceCents()) + " available.");
        }
        return new Pending(nonce(), "bill", "bill", source.id(), null, payee, amount, memo);
    }

    public Posting post(int userId, Pending pending) {
        if (!acceptable(pending)) {
            throw new FormException("That transfer is no longer available. Start again.");
        }
        try (Connection conn = connect()) {
            conn.setAutoCommit(false);
            try {
                Posting posting = postLocked(conn, userId, pending);
                conn.commit();
                return posting;
            } catch (FormException ex) {
                conn.rollback();
                throw ex;
            } catch (SQLException ex) {
                conn.rollback();
                if (isConstraint(ex)) {
                    String existing = confirmationForNonce(pending.nonce);
                    if (existing != null) {
                        return new Posting(existing, "This transfer was already posted.", true);
                    }
                }
                throw new FormException("Couldn't post that transfer. Review it again.", ex);
            }
        } catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public Account accountFor(String email, String kind) {
        String sql = """
                SELECT a.id, a.user_id, a.nickname, a.kind, a.account_number, a.routing_number,
                       a.balance_cents, a.opened_on, u.full_name AS owner_name
                FROM accounts a
                JOIN users u ON u.id = a.user_id
                WHERE u.email = ? AND a.kind = ?
                """;
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, email);
            ps.setString(2, kind);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("No " + kind + " account for " + email);
                }
                return readAccount(rs);
            }
        } catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public Account findAny(int accountId) {
        String sql = """
                SELECT a.id, a.user_id, a.nickname, a.kind, a.account_number, a.routing_number,
                       a.balance_cents, a.opened_on, u.full_name AS owner_name
                FROM accounts a JOIN users u ON u.id = a.user_id
                WHERE a.id = ?
                """;
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readAccount(rs) : null;
            }
        } catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public ReceiptRow receipt(int userId, String confirmation) {
        if (confirmation == null || !confirmation.matches("NW-[0-9A-F]{8}")) {
            return null;
        }
        String sql = """
                SELECT tr.confirmation, tr.scope, tr.kind, tr.amount_cents, tr.memo, tr.created_at,
                       tr.initiator_id, tr.from_account_id, tr.payee_name,
                       src.nickname AS from_nickname, src.account_number AS from_number,
                       dst.nickname AS to_nickname, dst.account_number AS to_number, dst.user_id AS to_user_id,
                       dst_user.full_name AS to_name
                FROM transfers tr
                JOIN accounts src ON src.id = tr.from_account_id
                LEFT JOIN accounts dst ON dst.id = tr.to_account_id
                LEFT JOIN users dst_user ON dst_user.id = dst.user_id
                WHERE tr.confirmation = ?
                """;
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, confirmation);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                int initiator = rs.getInt("initiator_id");
                int toUser = rs.getInt("to_user_id");
                boolean toUserPresent = !rs.wasNull();
                if (userId != initiator && !(toUserPresent && userId == toUser)) {
                    return null;
                }
                return new ReceiptRow(
                        rs.getString("confirmation"),
                        rs.getString("scope"),
                        rs.getString("kind"),
                        rs.getLong("amount_cents"),
                        rs.getString("memo"),
                        rs.getString("created_at"),
                        rs.getInt("from_account_id"),
                        rs.getString("from_nickname"),
                        rs.getString("from_number"),
                        rs.getString("to_nickname"),
                        rs.getString("to_number"),
                        rs.getString("to_name"),
                        rs.getString("payee_name"));
            }
        } catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public record ReceiptRow(
            String confirmation,
            String scope,
            String kind,
            long amountCents,
            String memo,
            String createdAt,
            int fromId,
            String fromNickname,
            String fromNumber,
            String toNickname,
            String toNumber,
            String toName,
            String payee) {}

    public void assertBalanced() {
        try (Connection conn = connect()) {
            assertBalanced(conn);
        } catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private Posting postLocked(Connection conn, int userId, Pending pending) throws SQLException {
        Account source = lockedAccount(conn, pending.fromId, userId);
        if (source == null) {
            throw new FormException("Choose an account of yours.");
        }
        long amount = pending.amountCents;
        if (amount <= 0 || amount > Money.MAX_CENTS) {
            throw new FormException("Enter a dollar amount like 25.00.");
        }
        Account dest = null;
        if ("own".equals(pending.scope) || "member".equals(pending.scope)) {
            dest = lockedAccount(conn, pending.toId, null);
            if (dest == null || dest.id() == source.id()) {
                throw new FormException("Choose where the money should go.");
            }
            if ("own".equals(pending.scope) && dest.userId() != userId) {
                throw new FormException("Choose one of your accounts.");
            }
            if ("member".equals(pending.scope) && dest.userId() == userId) {
                throw new FormException("That account is yours. Choose it from the list instead.");
            }
        } else if (!"bill".equals(pending.scope) || pending.payee == null || pending.payee.isBlank()) {
            throw new FormException("That transfer is no longer available. Start again.");
        }

        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE accounts SET balance_cents = balance_cents - ? WHERE id = ? AND balance_cents >= ?")) {
            ps.setLong(1, amount);
            ps.setInt(2, source.id());
            ps.setLong(3, amount);
            if (ps.executeUpdate() != 1) {
                Account fresh = lockedAccount(conn, source.id(), null);
                String available = fresh == null ? "$0.00" : Money.format(fresh.balanceCents());
                String name = fresh == null ? "That account" : fresh.nickname();
                throw new FormException(name + " has " + available + " available.");
            }
        }
        long sourceBalance = balanceOf(conn, source.id());
        String postedOn = LocalDate.now().toString();
        String description;
        String category;
        String destLabel;
        if ("bill".equals(pending.scope)) {
            description = pending.payee;
            category = "bill";
            destLabel = pending.payee;
        } else if ("own".equals(pending.scope)) {
            description = "Transfer to " + dest.nickname();
            category = "internal";
            destLabel = dest.nickname();
        } else {
            description = "Sent to " + dest.ownerName();
            category = "transfer";
            destLabel = dest.ownerName();
        }
        insertTx(conn, source.id(), postedOn, description, pending.memo, -amount, sourceBalance, category, pending.nonce);
        if (dest != null) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE accounts SET balance_cents = balance_cents + ? WHERE id = ?")) {
                ps.setLong(1, amount);
                ps.setInt(2, dest.id());
                ps.executeUpdate();
            }
            long destBalance = balanceOf(conn, dest.id());
            if ("own".equals(pending.scope)) {
                insertTx(conn, dest.id(), postedOn, "Transfer from " + source.nickname(), pending.memo,
                        amount, destBalance, "internal", pending.nonce);
            } else {
                insertTx(conn, dest.id(), postedOn, "Received from " + source.ownerName(), pending.memo,
                        amount, destBalance, "transfer", pending.nonce);
            }
        }
        String confirmation = "NW-" + HexFormat.of().withUpperCase().formatHex(randomBytes(4));
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO transfers
                  (confirmation, nonce, initiator_id, kind, scope, from_account_id, to_account_id,
                   payee_name, amount_cents, memo, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            ps.setString(1, confirmation);
            ps.setString(2, pending.nonce);
            ps.setInt(3, userId);
            ps.setString(4, pending.kind);
            ps.setString(5, pending.scope);
            ps.setInt(6, source.id());
            if (dest == null) {
                ps.setNull(7, java.sql.Types.INTEGER);
            } else {
                ps.setInt(7, dest.id());
            }
            if (pending.payee == null || pending.payee.isBlank()) {
                ps.setNull(8, java.sql.Types.VARCHAR);
            } else {
                ps.setString(8, pending.payee);
            }
            ps.setLong(9, amount);
            if (pending.memo == null || pending.memo.isBlank()) {
                ps.setNull(10, java.sql.Types.VARCHAR);
            } else {
                ps.setString(10, pending.memo);
            }
            ps.setString(11, Dates.nowStamp());
            ps.executeUpdate();
        }
        String message = switch (pending.scope) {
            case "own" -> "Moved " + Money.format(amount) + " from " + source.nickname() + " to " + destLabel + ".";
            case "member" -> "Sent " + Money.format(amount) + " to " + destLabel + " from " + source.nickname() + ".";
            default -> "Paid " + Money.format(amount) + " to " + destLabel + " from " + source.nickname() + ".";
        };
        return new Posting(confirmation, message, false);
    }

    private void init() {
        try (Connection conn = connect()) {
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA journal_mode = WAL");
            }
            String version = null;
            try (Statement st = conn.createStatement();
                    ResultSet master = st.executeQuery(
                            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'meta'")) {
                if (master.next()) {
                    try (PreparedStatement ps = conn.prepareStatement(
                            "SELECT value FROM meta WHERE key = 'seed_version'");
                            ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            version = rs.getString(1);
                        }
                    }
                }
            }
            if (!SEED_VERSION.equals(version)) {
                rebuild(conn);
            } else {
                assertBalanced(conn);
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Could not open the ledger", ex);
        }
    }

    private void rebuild(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA foreign_keys = OFF");
            st.execute("DROP TABLE IF EXISTS transactions");
            st.execute("DROP TABLE IF EXISTS transfers");
            st.execute("DROP TABLE IF EXISTS accounts");
            st.execute("DROP TABLE IF EXISTS users");
            st.execute("DROP TABLE IF EXISTS meta");
            for (String sql : SCHEMA) {
                st.execute(sql);
            }
            st.execute("PRAGMA foreign_keys = ON");
        }
        seed(conn);
    }

    private void seed(Connection conn) throws SQLException {
        List<SeedData.Customer> customers = SeedData.customers();
        String passwordHash = hashPassword(SeedData.DEMO_PASSWORD, randomBytes(16));
        conn.setAutoCommit(false);
        try {
            for (SeedData.Customer customer : customers) {
                int userId;
                try (PreparedStatement ps = conn.prepareStatement(
                        """
                        INSERT INTO users (email, full_name, city, member_since, password_hash)
                        VALUES (?, ?, ?, ?, ?)
                        """,
                        Statement.RETURN_GENERATED_KEYS)) {
                    SeedData.Person person = customer.person();
                    ps.setString(1, person.email());
                    ps.setString(2, person.fullName());
                    ps.setString(3, person.city());
                    ps.setString(4, person.memberSince());
                    ps.setString(5, passwordHash);
                    ps.executeUpdate();
                    try (ResultSet keys = ps.getGeneratedKeys()) {
                        userId = generatedKey(keys);
                    }
                }
                for (SeedData.Account account : customer.accounts()) {
                    int accountId;
                    try (PreparedStatement ps = conn.prepareStatement(
                            """
                            INSERT INTO accounts
                              (user_id, nickname, kind, account_number, routing_number, balance_cents, opened_on)
                            VALUES (?, ?, ?, ?, ?, ?, ?)
                            """,
                            Statement.RETURN_GENERATED_KEYS)) {
                        ps.setInt(1, userId);
                        ps.setString(2, account.nickname());
                        ps.setString(3, account.kind());
                        ps.setString(4, account.number());
                        ps.setString(5, ROUTING);
                        ps.setLong(6, account.balance());
                        ps.setString(7, account.openedOn());
                        ps.executeUpdate();
                        try (ResultSet keys = ps.getGeneratedKeys()) {
                            accountId = generatedKey(keys);
                        }
                    }
                    for (SeedData.Row row : account.rows()) {
                        insertTx(conn, accountId, row.postedAt(), row.description(), null,
                                row.amountCents(), row.balanceAfter(), row.category(), null);
                    }
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO meta (key, value) VALUES ('seed_version', ?)")) {
                ps.setString(1, SEED_VERSION);
                ps.executeUpdate();
            }
            assertBalanced(conn);
            conn.commit();
        } catch (RuntimeException ex) {
            conn.rollback();
            throw ex;
        } catch (Exception ex) {
            conn.rollback();
            throw new IllegalStateException(ex);
        } finally {
            conn.setAutoCommit(true);
        }
    }

    private static void assertBalanced(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement();
                ResultSet accounts = st.executeQuery("SELECT id, nickname, balance_cents FROM accounts")) {
            List<int[]> ids = new ArrayList<>();
            List<String> names = new ArrayList<>();
            List<Long> balances = new ArrayList<>();
            while (accounts.next()) {
                ids.add(new int[] {accounts.getInt("id")});
                names.add(accounts.getString("nickname"));
                balances.add(accounts.getLong("balance_cents"));
            }
            if (ids.isEmpty()) {
                throw new IllegalStateException("ledger has no accounts");
            }
            for (int i = 0; i < ids.size(); i++) {
                long running = 0;
                int count = 0;
                try (PreparedStatement ps = conn.prepareStatement(
                        """
                        SELECT amount_cents, balance_after_cents, description, posted_at
                        FROM transactions WHERE account_id = ? ORDER BY posted_at, id
                        """)) {
                    ps.setInt(1, ids.get(i)[0]);
                    try (ResultSet rows = ps.executeQuery()) {
                        while (rows.next()) {
                            count++;
                            running += rows.getLong("amount_cents");
                            if (running != rows.getLong("balance_after_cents")) {
                                throw new IllegalStateException(names.get(i) + " broke on "
                                        + rows.getString("posted_at") + " " + rows.getString("description"));
                            }
                        }
                    }
                }
                if (count == 0) {
                    throw new IllegalStateException(names.get(i) + " has no transactions");
                }
                if (running != balances.get(i)) {
                    throw new IllegalStateException(names.get(i) + " balance does not match the ledger");
                }
            }
        }
    }

    private Account requireOwned(int userId, String rawId) {
        if (rawId == null || !rawId.chars().allMatch(Character::isDigit)) {
            throw new FormException("Choose an account.");
        }
        Account account = owned(userId, Integer.parseInt(rawId));
        if (account == null) {
            throw new FormException("Choose an account of yours.");
        }
        return account;
    }

    private static String normalizeNumber(String raw) {
        String text = raw == null ? "" : raw.replaceAll("[\\s-]", "");
        if (!text.matches("\\d{10}")) {
            throw new FormException("Enter a 10-digit Northwater account number.");
        }
        return text;
    }

    private static String clean(String raw, int limit) {
        if (raw == null) {
            return "";
        }
        String text = raw.strip();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch < 32 || ch == 127) {
                throw new FormException("That field can't include line breaks.");
            }
        }
        if (text.length() > limit) {
            throw new FormException("Keep that field to " + limit + " characters.");
        }
        return text;
    }

    public static boolean acceptable(Pending pending) {
        if (pending == null || pending.nonce == null || pending.memo == null || pending.payee == null) {
            return false;
        }
        if (pending.amountCents <= 0 || pending.memo.length() > 80 || pending.payee.length() > 60) {
            return false;
        }
        return switch (pending.scope == null ? "" : pending.scope) {
            case "own", "member" -> pending.toId != null && "transfer".equals(pending.kind);
            case "bill" -> "bill".equals(pending.kind) && !pending.payee.isBlank();
            default -> false;
        };
    }

    private String nonce() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(16));
    }

    private byte[] randomBytes(int size) {
        byte[] bytes = new byte[size];
        random.nextBytes(bytes);
        return bytes;
    }

    private Connection connect() throws SQLException {
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath());
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA foreign_keys = ON");
            st.execute("PRAGMA busy_timeout = 5000");
        }
        return conn;
    }

    private static User readUser(ResultSet rs) throws SQLException {
        return new User(
                rs.getInt("id"),
                rs.getString("email"),
                rs.getString("full_name"),
                rs.getString("city"),
                rs.getString("member_since"));
    }

    private static Account readAccount(ResultSet rs) throws SQLException {
        return new Account(
                rs.getInt("id"),
                rs.getInt("user_id"),
                rs.getString("nickname"),
                rs.getString("kind"),
                rs.getString("account_number"),
                rs.getString("routing_number"),
                rs.getLong("balance_cents"),
                rs.getString("opened_on"),
                rs.getString("owner_name"));
    }

    private List<Tx> queryTx(String sql, Object... params) {
        try (Connection conn = connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                List<Tx> rows = new ArrayList<>();
                while (rs.next()) {
                    String memo = rs.getString("memo");
                    rows.add(new Tx(
                            rs.getInt("account_id"),
                            rs.getString("posted_at"),
                            rs.getString("description"),
                            memo == null ? "" : memo,
                            rs.getLong("amount_cents"),
                            rs.getLong("balance_after_cents"),
                            rs.getString("category"),
                            rs.getString("nickname"),
                            rs.getString("account_number")));
                }
                return rows;
            }
        } catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static Account lockedAccount(Connection conn, Integer accountId, Integer userId) throws SQLException {
        if (accountId == null) {
            return null;
        }
        String sql = """
                SELECT a.id, a.user_id, a.nickname, a.kind, a.account_number, a.routing_number,
                       a.balance_cents, a.opened_on, u.full_name AS owner_name
                FROM accounts a JOIN users u ON u.id = a.user_id
                WHERE a.id = ?
                """ + (userId == null ? "" : " AND a.user_id = ?");
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, accountId);
            if (userId != null) {
                ps.setInt(2, userId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readAccount(rs) : null;
            }
        }
    }

    private static int generatedKey(ResultSet keys) throws SQLException {
        if (!keys.next()) {
            throw new IllegalStateException("insert did not return a key");
        }
        return keys.getInt(1);
    }

    private static long balanceOf(Connection conn, int accountId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT balance_cents FROM accounts WHERE id = ?")) {
            ps.setInt(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("account disappeared");
                }
                return rs.getLong(1);
            }
        }
    }

    private static void insertTx(
            Connection conn,
            int accountId,
            String postedAt,
            String description,
            String memo,
            long amount,
            long balanceAfter,
            String category,
            String group) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO transactions
                  (account_id, posted_at, description, memo, amount_cents, balance_after_cents, category, transfer_group)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            ps.setInt(1, accountId);
            ps.setString(2, postedAt);
            ps.setString(3, description);
            if (memo == null || memo.isBlank()) {
                ps.setNull(4, java.sql.Types.VARCHAR);
            } else {
                ps.setString(4, memo);
            }
            ps.setLong(5, amount);
            ps.setLong(6, balanceAfter);
            ps.setString(7, category);
            if (group == null) {
                ps.setNull(8, java.sql.Types.VARCHAR);
            } else {
                ps.setString(8, group);
            }
            ps.executeUpdate();
        }
    }

    private String confirmationForNonce(String nonce) throws SQLException {
        try (Connection conn = connect()) {
            return confirmationForNonce(conn, nonce);
        }
    }

    private static String confirmationForNonce(Connection conn, String nonce) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT confirmation FROM transfers WHERE nonce = ?")) {
            ps.setString(1, nonce);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static boolean isConstraint(SQLException ex) {
        String message = ex.getMessage() == null ? "" : ex.getMessage();
        return ex.getErrorCode() == 19 || message.contains("UNIQUE") || message.contains("CONSTRAINT");
    }

    private static String like(String query) {
        return "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    private static String hashPassword(String password, byte[] salt) {
        try {
            KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, PBKDF2_ROUNDS, 256);
            byte[] digest = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return "pbkdf2_sha256$" + PBKDF2_ROUNDS + "$" + HexFormat.of().formatHex(salt) + "$"
                    + HexFormat.of().formatHex(digest);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Could not hash a password", ex);
        }
    }

    private static boolean verifyPassword(String password, String stored) {
        try {
            String[] parts = stored.split("\\$");
            if (parts.length != 4 || !"pbkdf2_sha256".equals(parts[0])) {
                return false;
            }
            int rounds = Integer.parseInt(parts[1]);
            if (rounds < 1 || rounds > 1_000_000) {
                return false;
            }
            byte[] salt = HexFormat.of().parseHex(parts[2]);
            byte[] expected = HexFormat.of().parseHex(parts[3]);
            KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, rounds, expected.length * 8);
            byte[] actual = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return MessageDigest.isEqual(actual, expected);
        } catch (GeneralSecurityException | IllegalArgumentException ex) {
            return false;
        }
    }

    private static final String[] SCHEMA = {
        """
        CREATE TABLE users (
          id INTEGER PRIMARY KEY,
          email TEXT NOT NULL UNIQUE,
          full_name TEXT NOT NULL,
          city TEXT NOT NULL,
          member_since TEXT NOT NULL,
          password_hash TEXT NOT NULL
        )
        """,
        """
        CREATE TABLE accounts (
          id INTEGER PRIMARY KEY,
          user_id INTEGER NOT NULL REFERENCES users(id),
          nickname TEXT NOT NULL,
          kind TEXT NOT NULL CHECK (kind IN ('checking', 'savings')),
          account_number TEXT NOT NULL UNIQUE,
          routing_number TEXT NOT NULL,
          balance_cents INTEGER NOT NULL CHECK (balance_cents >= 0),
          opened_on TEXT NOT NULL
        )
        """,
        """
        CREATE TABLE transactions (
          id INTEGER PRIMARY KEY,
          account_id INTEGER NOT NULL REFERENCES accounts(id),
          posted_at TEXT NOT NULL,
          description TEXT NOT NULL,
          memo TEXT,
          amount_cents INTEGER NOT NULL CHECK (amount_cents != 0),
          balance_after_cents INTEGER NOT NULL CHECK (balance_after_cents >= 0),
          category TEXT NOT NULL,
          transfer_group TEXT
        )
        """,
        """
        CREATE TABLE transfers (
          id INTEGER PRIMARY KEY,
          confirmation TEXT NOT NULL UNIQUE,
          nonce TEXT NOT NULL UNIQUE,
          initiator_id INTEGER NOT NULL REFERENCES users(id),
          kind TEXT NOT NULL CHECK (kind IN ('transfer', 'bill')),
          scope TEXT NOT NULL CHECK (scope IN ('own', 'member', 'bill')),
          from_account_id INTEGER NOT NULL REFERENCES accounts(id),
          to_account_id INTEGER REFERENCES accounts(id),
          payee_name TEXT,
          amount_cents INTEGER NOT NULL CHECK (amount_cents > 0),
          memo TEXT,
          created_at TEXT NOT NULL
        )
        """,
        """
        CREATE TABLE meta (
          key TEXT PRIMARY KEY,
          value TEXT NOT NULL
        )
        """,
        "CREATE INDEX idx_accounts_user ON accounts(user_id)",
        "CREATE INDEX idx_tx_account_date ON transactions(account_id, posted_at DESC, id DESC)"
    };
}
