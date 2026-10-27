package me.skerik.justauction.model;

public enum Sort {
    NEWEST("created_at DESC, id DESC"),
    ENDING_SOON("expires_at ASC, id ASC"),
    PRICE_ASC("price ASC, id ASC"),
    PRICE_DESC("price DESC, id DESC");

    private final String orderBy;

    Sort(String orderBy) {
        this.orderBy = orderBy;
    }

    /** Fixed SQL fragment (never user input). */
    public String orderBy() {
        return orderBy;
    }

    public Sort next() {
        Sort[] all = values();
        return all[(ordinal() + 1) % all.length];
    }
}
