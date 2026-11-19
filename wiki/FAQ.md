# FAQ

**Does it work on Spigot?**
No. JustAuction is Paper only: it uses `paper-plugin.yml`, Paper's plugin loader and Brigadier commands. Paper forks such as Purpur normally work but are not tested.

**Does it work on Folia?**
Not yet. The scheduling code is prepared for it, but there is no Folia build for 26.3 to test against, so it is marked as not supported.

**Which economy plugins work?**
Any plugin that registers an economy with Vault or VaultUnlocked: EssentialsX, CMI (through CMI Injector), and many others.

**Where does the sales fee go?**
Nowhere: it is removed from the economy. That makes it a money sink, which helps against inflation. Set `listing.sales-fee-percent: 0` to switch it off.

**Can players sell a full shulker box?**
Yes, unless it contains a blacklisted item and `blacklist.check-container-contents` is on (the default).

**Can two players buy the same item?**
No. Reserving a listing is a single guarded database update, so only one buyer can win it, and the other is turned away before any money is taken.

**What happens if the server crashes during a purchase?**
Every money step is journaled in the database. At the next start the purchase is either finished or reverted. See [Safety and Recovery](Safety-and-Recovery).

**What happens to items when a listing expires?**
They go to the seller's claims (`/ja claim`). The seller is told if online, and reminded on the next join.

**What if the buyer's inventory is full?**
The item waits in their claims. Nothing is dropped on the ground.

**Can a seller cancel an auction?**
Only while it has no bids. Admins can always remove it with `/ja admin remove <id>`, which refunds the top bidder.

**Can I share one auction house between several servers?**
Point every server to the same MariaDB/MySQL database. See [Storage and Database](Storage-and-Database).

**Can I give VIPs more listings?**
Yes, with `justauction.limit.<n>`, for example `justauction.limit.15`. The highest one wins. See [Commands and Permissions](Commands-and-Permissions#listing-limits).

**How do I turn off auctions and keep only fixed prices?**
Set `auction.enabled: false` and run `/ja reload`. Running auctions still finish.

**Can I translate the plugin?**
Yes, see [Messages and Localization](Messages-and-Localization).

**What does bStats collect, and how do I switch it off?**
Server count, Minecraft version, storage type, language, economy plugin, and whether auctions and announcements are on. Nothing personal. Set `metrics: false`, or switch bStats off globally in `plugins/bStats/config.yml`.
