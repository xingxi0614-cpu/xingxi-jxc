package com.group.scm.modules.inventory.controller;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InventoryKeywordTermsTest {

    @Test
    void blankGivesNoFilter() {
        assertEquals(List.of(), InventoryController.keywordTerms(null));
        assertEquals(List.of(), InventoryController.keywordTerms("  ，, "));
    }

    @Test
    void commasSplitGroupsSpacesSplitWords() {
        assertEquals(List.of(List.of("M10001"), List.of("M10002")),
                InventoryController.keywordTerms("M10001, M10002"));
        assertEquals(List.of(List.of("角铁", "150"), List.of("玛钢")),
                InventoryController.keywordTerms("角铁 150，玛钢"));
        assertEquals(List.of(List.of("L型", "角铁")), InventoryController.keywordTerms("L型　角铁"));
    }

    @Test
    void wildcardsAreEscaped() {
        assertEquals(List.of(List.of("100\\%"), List.of("a\\_b")), InventoryController.keywordTerms("100%,a_b"));
    }

    @Test
    void longInputIsCapped() {
        String many = String.join(",", java.util.stream.IntStream.range(0, 50).mapToObj(i -> "m" + i).toList());
        assertEquals(20, InventoryController.keywordTerms(many).size());
        assertEquals(50, InventoryController.keywordTerms("x".repeat(80)).get(0).get(0).length());
    }
}
