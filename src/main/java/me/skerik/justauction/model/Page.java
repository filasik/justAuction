package me.skerik.justauction.model;

import java.util.List;

public record Page<T>(List<T> items, int total) {
}
