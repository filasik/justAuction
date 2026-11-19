# Storage and Database

JustAuction stores listings, claims, transactions and pending refunds in a SQL database. All database work runs on a dedicated thread pool, so it never blocks the server's main thread.

## SQLite (default)

```yaml
storage:
  type: sqlite
  sqlite:
    file: justauction.db
    pool-size: 3
```

A single file in `plugins/JustAuction/`. Zero setup. Back it up by copying the file while the server is stopped.

## MariaDB / MySQL

Use this to keep the data on a database server, or to share one auction house between several servers.

```yaml
storage:
  type: mariadb
  mariadb:
    host: localhost
    port: 3306
    database: justauction
    user: justauction
    password: change-me
    pool-size: 6
    ssl-mode: ""
```

1. Create an empty database and a user with full rights on it:
   ```sql
   CREATE DATABASE justauction CHARACTER SET utf8mb4;
   CREATE USER 'justauction'@'%' IDENTIFIED BY 'a-strong-password';
   GRANT ALL PRIVILEGES ON justauction.* TO 'justauction'@'%';
   ```
2. Fill in the `mariadb` section and set `type: mariadb`.
3. Restart the server. The tables are created automatically.

The MariaDB JDBC driver talks to both MariaDB and MySQL. JustAuction is tested against **MySQL** (unit tests and live server runs); a real MariaDB server is untested so far.

### `ssl-mode`

| Value | Meaning |
| :--- | :--- |
| `""` | Driver default |
| `trust` | Encrypted, certificate not verified |
| `disable` | No TLS. Only on a trusted local network. |
| `verify-ca`, `verify-full` | Encrypted and verified. Needs a proper certificate. |

**MySQL 8+** with its default self-signed certificate fails with *"Cannot use authentication plugin caching_sha2_password with a Self signed certificates"*. Set `ssl-mode: trust`. The startup error message points this out too.

## Tables

| Table | Contents |
| :--- | :--- |
| `ja_listings` | Every listing and auction with its status, price, bids and the journal stage of a running purchase |
| `ja_claims` | Items waiting to be collected |
| `ja_transactions` | Completed sales, used by `/ja history` |
| `ja_refunds` | Outbid money that still has to be paid back |
| `ja_schema` | Schema version |

The schema is versioned. Migrations run automatically at startup; never edit the tables by hand while the server is running.

## Switching storage

There is no built-in converter between SQLite and MariaDB. Switching `storage.type` starts with an empty auction house. Before switching, let players collect their claims and let listings end, or keep the old database.
