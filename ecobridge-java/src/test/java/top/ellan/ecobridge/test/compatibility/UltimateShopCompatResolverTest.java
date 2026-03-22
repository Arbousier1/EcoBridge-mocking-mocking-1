package top.ellan.ecobridge.test.compatibility;

import org.junit.jupiter.api.Test;
import top.ellan.ecobridge.integration.platform.compat.UltimateShopCompat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UltimateShopCompatResolverTest {

    static class EventWithMethods {
        public boolean isBuyOrSell() { return false; }
        public int getAmount() { return 42; }
    }

    static class EventWithFields {
        @SuppressWarnings("unused")
        private final boolean buyOrSell = true;
        @SuppressWarnings("unused")
        private final int amount = 7;
    }

    static class EventLegacyFields {
        @SuppressWarnings("unused")
        private final boolean isBuy = false;
        @SuppressWarnings("unused")
        private final int multi = 3;
    }

    static class ItemWithMethods {
        public String getShop() { return "MainShop"; }
        public String getProduct() { return "Diamond_Block"; }
    }

    @Test
    void resolveBuyFlagByMethod() {
        assertFalse(UltimateShopCompat.resolveBuyFlag(new EventWithMethods()));
    }

    @Test
    void resolveAmountByMethod() {
        assertEquals(42, UltimateShopCompat.resolveAmount(new EventWithMethods()));
    }

    @Test
    void resolveBuyFlagByFieldFallback() {
        assertTrue(UltimateShopCompat.resolveBuyFlag(new EventWithFields()));
        assertFalse(UltimateShopCompat.resolveBuyFlag(new EventLegacyFields()));
    }

    @Test
    void resolveAmountByFieldFallback() {
        assertEquals(7, UltimateShopCompat.resolveAmount(new EventWithFields()));
        assertEquals(3, UltimateShopCompat.resolveAmount(new EventLegacyFields()));
    }

    @Test
    void resolveShopAndProductNormalization() {
        ItemWithMethods item = new ItemWithMethods();
        assertEquals("mainshop", UltimateShopCompat.resolveShopId(item));
        assertEquals("diamond_block", UltimateShopCompat.resolveProductId(item));
    }
}

