# Player Guide

This page is written for players. Feel free to link it from your server's rules or help pages.

## Selling an item

1. Hold the item (or stack) in your main hand.
2. Run `/ja sell <price>`, for example `/ja sell 500` or `/ja sell 2.5k`.

The whole stack in your hand goes on sale. The confirmation tells you the listing id, how long it runs and how much you will receive after the sales fee. If the server charges a listing fee, it is taken when you list and returned if listing fails.

A listing can be refused when the price is outside the server's limits, the item is on the blacklist (also items hidden inside a shulker box or bundle), you have reached your listing limit, or you cannot afford the listing fee. In each case the item stays in your hand.

When somebody buys your item, the money (minus the sales fee) goes straight to your balance.

## Buying

Open the market with `/ja` (or `/ah`, `/auction`). It shows 45 listings per page. The bottom row has:

| Button | What it does |
| :--- | :--- |
| Arrows | Previous / next page |
| Hopper | Change sorting: newest, ending soon, cheapest, most expensive |
| Chest | Your own listings |
| Ender chest | Your claims (the button shows how many items are waiting) |
| Paper | Current page and total listings |
| Compass / barrier | Shows the active search. Click the barrier to clear it. |

Click a fixed-price item to open the confirmation screen, then confirm to buy. The money is taken, the seller is paid, and the item is delivered to your inventory. If your inventory is full, it waits in your claims.

To search, run `/ja search <text>`. It matches item names, material names and enchantments, for example `/ja search sharpness` or `/ja search diamond sword`.

## Auctions

Items marked as auctions are bid on instead of bought. See [Auctions](Auctions) for the full rules. In short: click the item to bid the minimum or buy it out, or use `/ja bid <id> <amount>` for a higher bid. Your bid is taken when you place it, and you get it back automatically if somebody outbids you.

## Your listings

`/ja mylistings` (or the chest button) shows everything you have on sale. Click a listing to cancel it; the item goes to your claims. An auction that already has bids cannot be cancelled.

Listings that are not sold in time expire, and the item also goes to your claims. You get a chat message if you are online.

## Claims

Claims are where items wait for you: things you bought while your inventory was full, auctions you won while offline, and your own expired or cancelled listings. You are reminded when you join if anything is waiting.

- `/ja claim` puts everything that fits straight into your inventory.
- `/ja claims` (or the ender chest button) opens a menu where you can take items one by one or collect all.

> Unclaimed items are deleted after a number of days set by the server (7 by default). Collect them in time.

## History

`/ja history` lists your last 10 sales and purchases, with the time, item, price, what you earned and the other player.
