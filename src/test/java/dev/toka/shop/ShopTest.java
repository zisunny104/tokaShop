package dev.toka.shop;

import static org.junit.Assert.*;

import java.io.InputStream;
import java.util.Set;
import org.junit.Test;

public class ShopTest {
    @Test public void packPricesRoundTowardsTheShop() {
        assertEquals(10, Pricing.buyTotal(100, 64, 6));   // 9.375 up
        assertEquals(9, Pricing.sellTotal(100, 64, 6));   // 9.375 down
        assertEquals(1, Pricing.buyTotal(3, 64, 1));      // never free
        assertEquals(0, Pricing.sellTotal(3, 64, 1));
    }

    @Test public void taxRoundsUpAndZeroMeansNone() {
        assertEquals(0, Pricing.tax(1000, 0));
        assertEquals(5, Pricing.tax(100, 5));
        assertEquals(1, Pricing.tax(1, 5));
    }

    @Test public void shippedCatalogueFollowsThePriceRules() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/catalogue.json")) {
            var list = Catalogue.read(in);
            assertTrue(list.size() > 1000);
            var seen = new java.util.HashSet<String>();
            for (Product p : list) {
                assertTrue("duplicate key " + p.key(), seen.add(p.key()));
                assertTrue(p.key() + " pack", Set.of(1, 16, 32, 64).contains(p.pack()));
                assertTrue(p.key() + " buy", p.buy() == -1 || Set.of(0L, 3L, 7L, 9L).contains(p.buy() % 10));
                assertTrue(p.key() + " sell", p.sell() == -1 || Set.of(0L, 3L, 7L, 9L).contains(p.sell() % 10));
                if (p.canBuy() && p.canSell()) assertTrue(p.key() + " sells above buy", p.sell() <= p.buy());
                assertTrue(p.key() + " name", p.name("zh-TW") != null && !p.name("zh-TW").isBlank());
            }
            var cat = new Catalogue(list);
            for (var sections : Catalogue.LAYOUT.values()) for (String s : sections) assertFalse(s + " empty", cat.families(s).isEmpty());
        }
    }

    @Test public void overridesChangeNameAndPricesButNotInvalidOnes() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/catalogue.json")) {
            var list = Catalogue.read(in);
            Product p = list.get(0);
            var cat = Catalogue.with(list, java.util.Map.of(p.key(), new Catalogue.Override(0L, 13L, "X", false)), "zh-TW");
            Product q = cat.get(p.key());
            assertEquals(p.buy(), q.buy());
            assertEquals(13, q.sell());
            assertEquals("X", q.name("zh-TW"));
            var closed = Catalogue.with(list, java.util.Map.of(p.key(), new Catalogue.Override(null, null, null, true)), "zh-TW");
            assertFalse(closed.get(p.key()).canBuy());
        }
    }
}
