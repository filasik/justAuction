package me.skerik.justauction.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import me.skerik.justauction.model.Claim;
import me.skerik.justauction.model.ClaimReason;
import me.skerik.justauction.model.Listing;
import me.skerik.justauction.model.ListingStatus;
import me.skerik.justauction.model.Page;
import me.skerik.justauction.model.Sort;
import me.skerik.justauction.model.Transaction;
import org.jspecify.annotations.Nullable;

public final class SqlListingStore implements ListingStore {

    private static final String LISTING_COLUMNS =
            "id, seller, seller_name, item, material, amount, price, status, buyer, stage, created_at, expires_at";

    private final Database db;

    public SqlListingStore(Database db) {
        this.db = db;
    }

    @Override
    public CompletableFuture<Optional<Listing>> insert(NewListing n, int maxActive) {
        return db.supply(c -> Database.transaction(c, tx -> {
            try (PreparedStatement ps = tx.prepareStatement(
                    "SELECT COUNT(*) FROM ja_listings WHERE seller = ? AND status IN ('ACTIVE', 'RESERVED')")) {
                ps.setString(1, n.seller().toString());
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    if (rs.getInt(1) >= maxActive) return Optional.<Listing>empty();
                }
            }
            long id;
            try (PreparedStatement ps = tx.prepareStatement(
                    "INSERT INTO ja_listings (seller, seller_name, item, material, amount, search_text, price, status, "
                            + "stage, created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?, 'ACTIVE', 0, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, n.seller().toString());
                ps.setString(2, n.sellerName());
                ps.setBytes(3, n.item());
                ps.setString(4, n.material());
                ps.setInt(5, n.amount());
                ps.setString(6, truncate(n.searchText().toLowerCase(Locale.ROOT), 1000));
                ps.setLong(7, n.price());
                ps.setLong(8, n.createdAt());
                ps.setLong(9, n.expiresAt());
                ps.executeUpdate();
                id = generatedKey(ps);
            }
            return Optional.of(new Listing(id, n.seller(), n.sellerName(), n.item(), n.material(), n.amount(),
                    n.price(), ListingStatus.ACTIVE, null, 0, n.createdAt(), n.expiresAt()));
        }));
    }

    @Override
    public CompletableFuture<Optional<Listing>> get(long id) {
        return db.supply(c -> Optional.ofNullable(selectListing(c, id)));
    }

    @Override
    public CompletableFuture<Page<Listing>> browse(@Nullable String search, @Nullable UUID seller, Sort sort,
                                                   long now, int offset, int limit) {
        return db.supply(c -> {
            StringBuilder where = new StringBuilder(" WHERE status = 'ACTIVE' AND expires_at > ?");
            List<Object> args = new ArrayList<>();
            args.add(now);
            if (seller != null) {
                where.append(" AND seller = ?");
                args.add(seller.toString());
            }
            if (search != null && !search.isBlank()) {
                for (String word : search.toLowerCase(Locale.ROOT).strip().split("\\s+")) {
                    where.append(" AND search_text LIKE ? ESCAPE '!'");
                    args.add("%" + word.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%");
                }
            }
            int total;
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM ja_listings" + where)) {
                bind(ps, args);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    total = rs.getInt(1);
                }
            }
            List<Listing> items = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT " + LISTING_COLUMNS + " FROM ja_listings" + where
                    + " ORDER BY " + sort.orderBy() + " LIMIT ? OFFSET ?")) {
                List<Object> all = new ArrayList<>(args);
                all.add(limit);
                all.add(offset);
                bind(ps, all);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) items.add(mapListing(rs));
                }
            }
            return new Page<>(items, total);
        });
    }

    @Override
    public CompletableFuture<List<Listing>> activeOf(UUID seller) {
        return db.supply(c -> {
            List<Listing> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT " + LISTING_COLUMNS
                    + " FROM ja_listings WHERE seller = ? AND status = 'ACTIVE' ORDER BY expires_at ASC, id ASC")) {
                ps.setString(1, seller.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(mapListing(rs));
                }
            }
            return out;
        });
    }

    @Override
    public CompletableFuture<Boolean> reserve(long id, UUID buyer, long now) {
        return db.supply(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE ja_listings SET status = 'RESERVED', buyer = ?, "
                    + "stage = 0, reserved_at = ? WHERE id = ? AND status = 'ACTIVE' AND expires_at > ? AND seller <> ?")) {
                ps.setString(1, buyer.toString());
                ps.setLong(2, now);
                ps.setLong(3, id);
                ps.setLong(4, now);
                ps.setString(5, buyer.toString());
                return ps.executeUpdate() == 1;
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> setStage(long id, int stage) {
        return db.supply(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE ja_listings SET stage = ? WHERE id = ? AND status = 'RESERVED'")) {
                ps.setInt(1, stage);
                ps.setLong(2, id);
                return ps.executeUpdate() == 1;
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> revert(long id) {
        return db.supply(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE ja_listings SET status = 'ACTIVE', buyer = NULL, "
                    + "stage = 0, reserved_at = NULL WHERE id = ? AND status = 'RESERVED'")) {
                ps.setLong(1, id);
                return ps.executeUpdate() == 1;
            }
        });
    }

    @Override
    public CompletableFuture<Optional<Long>> complete(long id, UUID buyer, String buyerName, long fee, long now) {
        return db.supply(c -> Database.transaction(c, tx -> {
            Listing l = selectListing(tx, id);
            if (l == null || l.status() != ListingStatus.RESERVED || !buyer.equals(l.buyer())) {
                return Optional.<Long>empty();
            }
            try (PreparedStatement ps = tx.prepareStatement(
                    "UPDATE ja_listings SET status = 'SOLD' WHERE id = ? AND status = 'RESERVED' AND buyer = ?")) {
                ps.setLong(1, id);
                ps.setString(2, buyer.toString());
                if (ps.executeUpdate() != 1) return Optional.<Long>empty();
            }
            try (PreparedStatement ps = tx.prepareStatement("INSERT INTO ja_transactions (listing_id, seller, "
                    + "seller_name, buyer, buyer_name, material, amount, price, fee, created_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                ps.setLong(1, id);
                ps.setString(2, l.seller().toString());
                ps.setString(3, l.sellerName());
                ps.setString(4, buyer.toString());
                ps.setString(5, buyerName);
                ps.setString(6, l.material());
                ps.setInt(7, l.amount());
                ps.setLong(8, l.price());
                ps.setLong(9, fee);
                ps.setLong(10, now);
                ps.executeUpdate();
            }
            return Optional.of(insertClaim(tx, buyer, l.item(), l.material(), l.amount(), ClaimReason.PURCHASE, now));
        }));
    }

    @Override
    public CompletableFuture<CancelOutcome> cancel(long id, @Nullable UUID actor, boolean admin, ClaimReason reason) {
        return db.supply(c -> Database.transaction(c, tx -> {
            Listing l = selectListing(tx, id);
            if (l == null) return new CancelOutcome(null, CancelFailure.NOT_FOUND);
            if (l.status() != ListingStatus.ACTIVE) return new CancelOutcome(null, CancelFailure.NOT_ACTIVE);
            if (!admin && !l.seller().equals(actor)) return new CancelOutcome(null, CancelFailure.NOT_OWNER);
            try (PreparedStatement ps = tx.prepareStatement(
                    "UPDATE ja_listings SET status = 'CANCELLED' WHERE id = ? AND status = 'ACTIVE'")) {
                ps.setLong(1, id);
                if (ps.executeUpdate() != 1) return new CancelOutcome(null, CancelFailure.NOT_ACTIVE);
            }
            long claim = insertClaim(tx, l.seller(), l.item(), l.material(), l.amount(), reason, System.currentTimeMillis());
            return new CancelOutcome(new Cancelled(l, claim), null);
        }));
    }

    @Override
    public CompletableFuture<List<Expired>> expireDue(long now, int limit) {
        return db.supply(c -> Database.transaction(c, tx -> {
            List<Listing> due = new ArrayList<>();
            try (PreparedStatement ps = tx.prepareStatement("SELECT " + LISTING_COLUMNS
                    + " FROM ja_listings WHERE status = 'ACTIVE' AND expires_at <= ? ORDER BY expires_at LIMIT ?")) {
                ps.setLong(1, now);
                ps.setInt(2, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) due.add(mapListing(rs));
                }
            }
            List<Expired> out = new ArrayList<>();
            for (Listing l : due) {
                try (PreparedStatement ps = tx.prepareStatement(
                        "UPDATE ja_listings SET status = 'EXPIRED' WHERE id = ? AND status = 'ACTIVE'")) {
                    ps.setLong(1, l.id());
                    if (ps.executeUpdate() != 1) continue;
                }
                long claim = insertClaim(tx, l.seller(), l.item(), l.material(), l.amount(), ClaimReason.EXPIRED, now);
                out.add(new Expired(l.seller(), claim, l));
            }
            return out;
        }));
    }

    @Override
    public CompletableFuture<List<Listing>> reservedBefore(long reservedAt) {
        return db.supply(c -> {
            List<Listing> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT " + LISTING_COLUMNS
                    + " FROM ja_listings WHERE status = 'RESERVED' AND (reserved_at IS NULL OR reserved_at <= ?)")) {
                ps.setLong(1, reservedAt);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(mapListing(rs));
                }
            }
            return out;
        });
    }

    @Override
    public CompletableFuture<List<Claim>> claims(UUID owner) {
        return db.supply(c -> {
            List<Claim> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT id, owner, item, material, amount, reason, created_at "
                    + "FROM ja_claims WHERE owner = ? ORDER BY id ASC")) {
                ps.setString(1, owner.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(mapClaim(rs));
                }
            }
            return out;
        });
    }

    @Override
    public CompletableFuture<Integer> claimCount(UUID owner) {
        return db.supply(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM ja_claims WHERE owner = ?")) {
                ps.setString(1, owner.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getInt(1);
                }
            }
        });
    }

    @Override
    public CompletableFuture<Long> addClaim(UUID owner, byte[] item, String material, int amount,
                                            ClaimReason reason, long now) {
        return db.supply(c -> insertClaim(c, owner, item, material, amount, reason, now));
    }

    @Override
    public CompletableFuture<Optional<Claim>> takeClaim(long id, UUID owner) {
        return db.supply(c -> Database.transaction(c, tx -> {
            Claim claim = null;
            try (PreparedStatement ps = tx.prepareStatement("SELECT id, owner, item, material, amount, reason, created_at "
                    + "FROM ja_claims WHERE id = ? AND owner = ?")) {
                ps.setLong(1, id);
                ps.setString(2, owner.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) claim = mapClaim(rs);
                }
            }
            if (claim == null) return Optional.<Claim>empty();
            try (PreparedStatement ps = tx.prepareStatement("DELETE FROM ja_claims WHERE id = ? AND owner = ?")) {
                ps.setLong(1, id);
                ps.setString(2, owner.toString());
                if (ps.executeUpdate() != 1) return Optional.<Claim>empty();
            }
            return Optional.of(claim);
        }));
    }

    @Override
    public CompletableFuture<Integer> purgeClaims(long olderThan) {
        return db.supply(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM ja_claims WHERE created_at < ?")) {
                ps.setLong(1, olderThan);
                return ps.executeUpdate();
            }
        });
    }

    @Override
    public CompletableFuture<List<Transaction>> history(UUID player, int limit) {
        return db.supply(c -> {
            List<Transaction> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT id, listing_id, seller, seller_name, buyer, buyer_name, "
                    + "material, amount, price, fee, created_at FROM ja_transactions WHERE seller = ? OR buyer = ? "
                    + "ORDER BY created_at DESC, id DESC LIMIT ?")) {
                ps.setString(1, player.toString());
                ps.setString(2, player.toString());
                ps.setInt(3, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new Transaction(rs.getLong(1), rs.getLong(2), UUID.fromString(rs.getString(3)),
                                rs.getString(4), UUID.fromString(rs.getString(5)), rs.getString(6), rs.getString(7),
                                rs.getInt(8), rs.getLong(9), rs.getLong(10), rs.getLong(11)));
                    }
                }
            }
            return out;
        });
    }

    // ---- helpers ----

    private static @Nullable Listing selectListing(Connection c, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT " + LISTING_COLUMNS + " FROM ja_listings WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapListing(rs) : null;
            }
        }
    }

    private static long insertClaim(Connection c, UUID owner, byte[] item, String material, int amount,
                                    ClaimReason reason, long now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO ja_claims (owner, item, material, amount, reason, "
                + "created_at) VALUES (?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, owner.toString());
            ps.setBytes(2, item);
            ps.setString(3, material);
            ps.setInt(4, amount);
            ps.setString(5, reason.name());
            ps.setLong(6, now);
            ps.executeUpdate();
            return generatedKey(ps);
        }
    }

    private static long generatedKey(PreparedStatement ps) throws SQLException {
        try (ResultSet keys = ps.getGeneratedKeys()) {
            if (!keys.next()) throw new SQLException("no generated key");
            return keys.getLong(1);
        }
    }

    private static Listing mapListing(ResultSet rs) throws SQLException {
        String buyer = rs.getString(9);
        return new Listing(rs.getLong(1), UUID.fromString(rs.getString(2)), rs.getString(3), rs.getBytes(4),
                rs.getString(5), rs.getInt(6), rs.getLong(7), ListingStatus.valueOf(rs.getString(8)),
                buyer == null ? null : UUID.fromString(buyer), rs.getInt(10), rs.getLong(11), rs.getLong(12));
    }

    private static Claim mapClaim(ResultSet rs) throws SQLException {
        return new Claim(rs.getLong(1), UUID.fromString(rs.getString(2)), rs.getBytes(3), rs.getString(4),
                rs.getInt(5), ClaimReason.valueOf(rs.getString(6)), rs.getLong(7));
    }

    private static void bind(PreparedStatement ps, List<Object> args) throws SQLException {
        for (int i = 0; i < args.size(); i++) ps.setObject(i + 1, args.get(i));
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
