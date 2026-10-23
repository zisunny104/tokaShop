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

    @Test public void pricesAreMinusOneOrPositive() {
        assertEquals(Long.valueOf(-1), Pricing.parsePrice(" -1 "));
        assertEquals(Long.valueOf(130), Pricing.parsePrice("130"));
        assertNull(Pricing.parsePrice("0"));
        assertNull(Pricing.parsePrice("-5"));
        assertNull(Pricing.parsePrice("abc"));
        assertNull(Pricing.parsePrice(""));
        assertNull(Pricing.parsePrice(null));
    }

    @Test public void pagingKeepsPagesInRange() {
        assertEquals(1, Paging.pages(0, 5));
        assertEquals(3, Paging.pages(12, 5));
        assertEquals(2, Paging.clamp(9, 3));
        assertEquals(0, Paging.clamp(-4, 3));
        assertEquals(10, Paging.from(2, 5));
        assertEquals(12, Paging.to(2, 5, 12));
    }

    @Test public void disabledProductKeepsItsPricesWhenViewedAsEnabled() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/catalogue.json")) {
            Product p = Catalogue.read(in).get(0);
            var off = new Catalogue.Override(null, null, null, true);
            assertFalse(Catalogue.apply(p, off, "zh-TW", false).canBuy());
            assertEquals(p.buy(), Catalogue.apply(p, off, "zh-TW", true).buy());
        }
    }

    @Test public void searchFindsByCodeInAnyCase() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/catalogue.json")) {
            var cat = new Catalogue(Catalogue.read(in));
            assertTrue(cat.search("a1001", 10).stream().anyMatch(p -> p.key().equals("A1001")));
        }
    }

    @Test public void langFilesHaveTheSameKeys() throws Exception {
        assertEquals(keys("/lang/zh-TW.yml"), keys("/lang/en-US.yml"));
    }

    private java.util.Set<String> keys(String path) throws Exception {
        try (var in = getClass().getResourceAsStream(path)) {
            var out = new java.util.TreeSet<String>();
            flatten("", new org.yaml.snakeyaml.Yaml().load(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)), out);
            return out;
        }
    }

    private void flatten(String prefix, Object node, java.util.Set<String> out) {
        if (node instanceof java.util.Map<?, ?> m) m.forEach((k, v) -> flatten(prefix.isEmpty() ? String.valueOf(k) : prefix + "." + k, v, out));
        else out.add(prefix);
    }
}
