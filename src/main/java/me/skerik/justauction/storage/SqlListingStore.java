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
import me.skerik.justauction.model.ListingKind;
import me.skerik.justauction.model.ListingStatus;
import me.skerik.justauction.model.Page;
import me.skerik.justauction.model.Refund;
import me.skerik.justauction.model.Sort;
import me.skerik.justauction.model.Transaction;
import org.jspecify.annotations.Nullable;

public final class SqlListingStore implements ListingStore {

    private static final String LISTING_COLUMNS =
            "id, seller, seller_name, item, material, amount, price, status, buyer, stage, created_at, expires_at, "
                    + "kind, buyout, bid, bidder, bidder_name, bid_count";

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
                            + "stage, created_at, expires_at, kind, buyout) VALUES (?, ?, ?, ?, ?, ?, ?, 'ACTIVE', 0, ?, ?, ?, ?)",
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
                ps.setString(10, n.kind().name());
                ps.setObject(11, n.buyout());
                ps.executeUpdate();
                id = generatedKey(ps);
            }
            return Optional.of(new Listing(id, n.seller(), n.sellerName(), n.item(), n.material(), n.amount(),
                    n.price(), ListingStatus.ACTIVE, null, 0, n.createdAt(), n.expiresAt(), n.kind(), n.buyout(),
                    null, null, null, 0));
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
                    + "stage = 0, reserved_at = ? WHERE id = ? AND status = 'ACTIVE' AND kind = 'FIXED' AND expires_at > ? AND seller <> ?")) {
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
                ps.setLong(8, l.finalPrice());
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
            if (!admin && l.bidder() != null) return new CancelOutcome(null, CancelFailure.HAS_BIDS);
            try (PreparedStatement ps = tx.prepareStatement(
                    "UPDATE ja_listings SET status = 'CANCELLED' WHERE id = ? AND status = 'ACTIVE'")) {
                ps.setLong(1, id);
                if (ps.executeUpdate() != 1) return new CancelOutcome(null, CancelFailure.NOT_ACTIVE);
            }
            long now = System.currentTimeMillis();
            long claim = insertClaim(tx, l.seller(), l.item(), l.material(), l.amount(), reason, now);
            long refund = l.bidder() == null ? 0 : insertRefund(tx, l.bidder(), l.bid(), l.id(), now);
            return new CancelOutcome(new Cancelled(l, claim, refund), null);
        }));
    }

    @Override
    public CompletableFuture<List<Expired>> expireDue(long now, int limit) {
        return db.supply(c -> Database.transaction(c, tx -> {
            List<Listing> due = new ArrayList<>();
            try (PreparedStatement ps = tx.prepareStatement("SELECT " + LISTING_COLUMNS
                    + " FROM ja_listings WHERE status = 'ACTIVE' AND bidder IS NULL AND expires_at <= ? ORDER BY expires_at LIMIT ?")) {
                ps.setLong(1, now);
                ps.setInt(2, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) due.add(mapListing(rs));
                }
            }
            List<Expired> out = new ArrayList<>();
            for (Listing l : due) {
                try (PreparedStatement ps = tx.prepareStatement(
                        "UPDATE ja_listings SET status = 'EXPIRED' WHERE id = ? AND status = 'ACTIVE' AND bidder IS NULL")) {
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
    public CompletableFuture<BidOutcome> bid(long id, UUID bidder, String bidderName, long amount, int expectedBidCount,
                                             long now, long snipeWindowMs, boolean endNow) {
        return db.supply(c -> Database.transaction(c, tx -> {
            Listing l = selectListing(tx, id);
            if (l == null || l.status() != ListingStatus.ACTIVE || !l.isAuction() || l.expiresAt() <= now
                    || l.bidCount() != expectedBidCount || l.seller().equals(bidder)) {
                return BidOutcome.rejected();
            }
            long expiresAt = l.expiresAt();
            if (endNow) expiresAt = now;
            else if (snipeWindowMs > 0 && expiresAt - now < snipeWindowMs) expiresAt = now + snipeWindowMs;
            try (PreparedStatement ps = tx.prepareStatement("UPDATE ja_listings SET bid = ?, bidder = ?, bidder_name = ?, "
                    + "bid_count = bid_count + 1, expires_at = ? WHERE id = ? AND status = 'ACTIVE' AND kind = 'AUCTION' "
                    + "AND bid_count = ? AND expires_at > ?")) {
                ps.setLong(1, amount);
                ps.setString(2, bidder.toString());
                ps.setString(3, bidderName);
                ps.setLong(4, expiresAt);
                ps.setLong(5, id);
                ps.setInt(6, expectedBidCount);
                ps.setLong(7, now);
                if (ps.executeUpdate() != 1) return BidOutcome.rejected();
            }
            long refund = l.bidder() == null ? 0 : insertRefund(tx, l.bidder(), l.bid(), id, now);
            return new BidOutcome(true, refund, l.bidder(), l.bid() == null ? 0 : l.bid(), expiresAt);
        }));
    }

    @Override
    public CompletableFuture<List<Listing>> dueAuctions(long now, int limit) {
        return db.supply(c -> {
            List<Listing> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT " + LISTING_COLUMNS + " FROM ja_listings WHERE "
                    + "status = 'ACTIVE' AND kind = 'AUCTION' AND bidder IS NOT NULL AND expires_at <= ? "
                    + "ORDER BY expires_at LIMIT ?")) {
                ps.setLong(1, now);
                ps.setInt(2, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(mapListing(rs));
                }
            }
            return out;
        });
    }

    @Override
    public CompletableFuture<Boolean> reserveWinner(long id, long now) {
        return db.supply(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE ja_listings SET status = 'RESERVED', buyer = bidder, "
                    + "stage = 1, reserved_at = ? WHERE id = ? AND status = 'ACTIVE' AND kind = 'AUCTION' "
                    + "AND bidder IS NOT NULL AND expires_at <= ?")) {
                ps.setLong(1, now);
                ps.setLong(2, id);
                ps.setLong(3, now);
                return ps.executeUpdate() == 1;
            }
        });
    }

    @Override
    public CompletableFuture<Optional<Long>> abortSale(long id, long now) {
        return db.supply(c -> Database.transaction(c, tx -> {
            Listing l = selectListing(tx, id);
            if (l == null || l.status() != ListingStatus.RESERVED) return Optional.<Long>empty();
            try (PreparedStatement ps = tx.prepareStatement(
                    "UPDATE ja_listings SET status = 'EXPIRED' WHERE id = ? AND status = 'RESERVED'")) {
                ps.setLong(1, id);
                if (ps.executeUpdate() != 1) return Optional.<Long>empty();
            }
            return Optional.of(insertClaim(tx, l.seller(), l.item(), l.material(), l.amount(), ClaimReason.EXPIRED, now));
        }));
    }

    @Override
    public CompletableFuture<List<Refund>> refundsBefore(long createdBefore) {
        return db.supply(c -> {
            List<Refund> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT id, player, amount, listing_id, created_at "
                    + "FROM ja_refunds WHERE created_at <= ? ORDER BY id")) {
                ps.setLong(1, createdBefore);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new Refund(rs.getLong(1), UUID.fromString(rs.getString(2)), rs.getLong(3),
                                rs.getLong(4), rs.getLong(5)));
                    }
                }
            }
            return out;
        });
    }

    @Override
    public CompletableFuture<Boolean> deleteRefund(long id) {
        return db.supply(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM ja_refunds WHERE id = ?")) {
                ps.setLong(1, id);
                return ps.executeUpdate() == 1;
            }
        });
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

    private static long insertRefund(Connection c, UUID player, long amount, long listingId, long now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO ja_refunds (player, amount, listing_id, created_at) VALUES (?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, player.toString());
            ps.setLong(2, amount);
            ps.setLong(3, listingId);
            ps.setLong(4, now);
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
        String bidder = rs.getString(16);
        long bid = rs.getLong(15);
        boolean hasBid = !rs.wasNull();
        long buyout = rs.getLong(14);
        boolean hasBuyout = !rs.wasNull();
        return new Listing(rs.getLong(1), UUID.fromString(rs.getString(2)), rs.getString(3), rs.getBytes(4),
                rs.getString(5), rs.getInt(6), rs.getLong(7), ListingStatus.valueOf(rs.getString(8)),
                buyer == null ? null : UUID.fromString(buyer), rs.getInt(10), rs.getLong(11), rs.getLong(12),
                ListingKind.valueOf(rs.getString(13)), hasBuyout ? buyout : null, hasBid ? bid : null,
                bidder == null ? null : UUID.fromString(bidder), rs.getString(17), rs.getInt(18));
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
