package dev.toka.shop;

import static org.junit.Assert.*;

import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

public class StallTest {
    private static Stock.Line line(int id, int damage, String tag, String label) {
        return new Stock.Line(Stock.Line.keyOf(id, damage, tag), id, damage, tag, label, 0, 0);
    }

    private static StallBook book(Map<String, JsonObject> saved) {
        return new StallBook(new StallBook.Persist() {
            @Override public void save(String key, JsonObject document) { saved.put(key, document); }

            @Override public void delete(String key) { saved.remove(key); }
        });
    }

    @Test public void namesLoseColourCodesAndAreCut() {
        assertEquals("Fish Shop", StallRules.cleanName("  §aFish   §lShop\n"));
        assertEquals("a", StallRules.cleanName("a§b"));
        assertEquals("", StallRules.cleanName("§"));
        assertEquals(StallRules.NAME_MAX, StallRules.cleanName("x".repeat(50)).length());
        assertFalse(StallRules.validName(""));
        assertFalse(StallRules.validName(null));
        assertEquals(StallRules.nameKey("Fish SHOP"), StallRules.nameKey("fish shop"));
    }

    @Test public void formNumbersAreBounded() {
        assertEquals(Long.valueOf(5), StallRules.parseNumber(" 5 ", 1, 10));
        assertNull(StallRules.parseNumber("0", 1, 10));
        assertNull(StallRules.parseNumber("11", 1, 10));
        assertNull(StallRules.parseNumber("-3", 0, 10));
        assertNull(StallRules.parseNumber("1e3", 0, 10));
        assertNull(StallRules.parseNumber("", 0, 10));
        assertNull(StallRules.parseNumber(null, 0, 10));
        assertNull(StallRules.parseNumber("99999999999999999999", 0, Long.MAX_VALUE));
    }

    @Test public void priceTimesPiecesNeverOverflows() {
        assertEquals(300, StallRules.total(3, 100));
        assertEquals(-1, StallRules.total(Long.MAX_VALUE, 2));
        assertEquals(-1, StallRules.total(0, 5));
        assertEquals(-1, StallRules.total(5, 0));
        assertEquals(15, Pricing.tax(StallRules.total(100, 3), 5));
    }

    @Test public void stacksAndPiecesAddUp() {
        assertEquals(130, StallRules.pieces(2L, 2L, 64, 500));
        assertEquals(1, StallRules.pieces(0L, 1L, 64, 500));
        assertEquals(-1, StallRules.pieces(0L, 0L, 64, 500));
        assertEquals(-1, StallRules.pieces(9L, 0L, 64, 500));
        assertEquals(-1, StallRules.pieces(null, 1L, 64, 500));
        assertEquals(-1, StallRules.pieces(-1L, 1L, 64, 500));
        assertEquals(5, StallRules.pieces(5L, 0L, 1, 500));
    }

    @Test public void equalStacksMergeIntoOneLineAndTakeTheNewPrice() {
        Stock stock = new Stock();
        assertEquals(Stock.Result.OK, stock.add(line(1, 0, "", "Stone"), 10, 5, 27));
        assertEquals(Stock.Result.OK, stock.add(line(1, 0, "", "Stone"), 5, 7, 27));
        assertEquals(1, stock.size());
        assertEquals(15, stock.find("1:0:").count());
        assertEquals(7, stock.find("1:0:").price());
        // another damage or another NBT is another product
        stock.add(line(1, 1, "", "Granite"), 1, 5, 27);
        stock.add(line(1, 0, "AAEC", "Stone"), 1, 5, 27);
        assertEquals(3, stock.size());
        assertEquals(17, stock.pieces());
    }

    @Test public void distinctProductsAreLimitedButMergingIsNot() {
        Stock stock = new Stock();
        assertEquals(Stock.Result.OK, stock.add(line(1, 0, "", "A"), 1, 1, 2));
        assertEquals(Stock.Result.OK, stock.add(line(2, 0, "", "B"), 1, 1, 2));
        assertEquals(Stock.Result.FULL, stock.add(line(3, 0, "", "C"), 1, 1, 2));
        assertEquals(Stock.Result.OK, stock.add(line(2, 0, "", "B"), 1, 1, 2));
        assertEquals(Stock.Result.TOO_MANY, stock.add(line(2, 0, "", "B"), StallRules.STOCK_MAX, 1, 2));
        assertEquals(Stock.Result.MISSING, stock.add(line(2, 0, "", "B"), 0, 1, 2));
    }

    @Test public void takingRemovesEmptyLinesAndRefusesTooMuch() {
        Stock stock = new Stock();
        stock.add(line(1, 0, "", "A"), 10, 3, 5);
        assertFalse(stock.take("1:0:", 11));
        assertFalse(stock.take("9:0:", 1));
        assertFalse(stock.take("1:0:", 0));
        assertTrue(stock.take("1:0:", 4));
        assertEquals(6, stock.find("1:0:").count());
        assertEquals(3, stock.find("1:0:").price());
        assertTrue(stock.take("1:0:", 6));
        assertTrue(stock.empty());
        assertFalse(stock.price("1:0:", 4));
    }

    @Test public void searchFindsByLabelInAnyCase() {
        Stock stock = new Stock();
        stock.add(line(1, 0, "", "Diamond Sword"), 1, 9, 5);
        stock.add(line(2, 0, "", "Stone"), 1, 9, 5);
        assertEquals(1, stock.search("sword").size());
        assertEquals(2, stock.search("").size());
        assertEquals(0, stock.search("zzz").size());
    }

    @Test public void aStallSurvivesItsDocument() {
        UUID owner = UUID.randomUUID();
        Stall stall = new Stall(Stall.newId(), owner, "Owner", "Fish Shop");
        stall.stock.add(line(5, 3, "AQID", "Named"), 12, 40, 27);
        stall.income = 900;
        stall.owed.put("sale-1", 80L);
        stall.signed = true;
        stall.world = "w";
        stall.x = 1;
        stall.y = 64;
        stall.z = -3;
        stall.claims = new ArrayList<>(List.of("c1", "c2"));
        stall.locked = true;
        stall.landOk = false;
        Stall back = Stall.fromJson(stall.id, stall.toJson());
        assertNotNull(back);
        assertEquals(owner, back.owner);
        assertEquals("Fish Shop", back.name);
        assertEquals(12, back.stock.find("5:3:AQID").count());
        assertEquals(40, back.stock.find("5:3:AQID").price());
        assertEquals("Named", back.stock.find("5:3:AQID").label());
        assertEquals(900, back.income);
        assertEquals(Map.of("sale-1", 80L), back.owed);
        assertEquals("w:1:64:-3", back.signKey());
        assertEquals(List.of("c1", "c2"), back.claims);
        assertTrue(back.locked);
        assertFalse(back.landOk);
        assertNull(Stall.fromJson("x", new JsonObject()));
    }

    @Test public void closedWhenSwitchedOffLockedOrLandIsLost() {
        Stall s = new Stall("a", UUID.randomUUID(), "O", "N");
        assertTrue(s.selling(true));
        s.open = false;
        assertFalse(s.selling(true));
        s.open = true;
        s.locked = true;
        assertFalse(s.selling(true));
        s.locked = false;
        s.signed = true;
        s.landOk = false;
        assertFalse(s.selling(true));
        // without tokaLand there is nobody to ask, so the land flag does not count
        assertTrue(s.selling(false));
    }

    @Test public void namesAreUniqueEverywhereAndLimitedPerOwner() {
        Map<String, JsonObject> saved = new HashMap<>();
        StallBook book = book(saved);
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        Stall one = book.create(a, "A", "  §cFish Shop ", 2);
        assertNotNull(one);
        assertEquals("Fish Shop", one.name);
        assertEquals(1, saved.size());
        assertNull(book.create(b, "B", "fish shop", 2));
        assertEquals(StallBook.Problem.TAKEN, book.check(b, "FISH SHOP", 2, null));
        assertEquals(StallBook.Problem.BAD_NAME, book.check(b, " §a ", 2, null));
        assertNotNull(book.create(a, "A", "Second", 2));
        assertEquals(StallBook.Problem.LIMIT, book.check(a, "Third", 2, null));
        assertNotNull(book.create(b, "B", "Third", 2));
        assertEquals(2, book.of(a).size());
        assertSame(one, book.byName("fish SHOP"));
    }

    @Test public void renameKeepsIndexesRight() {
        StallBook book = book(new HashMap<>());
        UUID a = UUID.randomUUID();
        Stall one = book.create(a, "A", "Old", 3), two = book.create(a, "A", "Other", 3);
        assertFalse(book.rename(one, "other"));
        assertTrue(book.rename(one, "OLD"));
        assertTrue(book.rename(one, "New"));
        assertNull(book.byName("Old"));
        assertSame(one, book.byName("new"));
        assertSame(two, book.byName("Other"));
    }

    @Test public void signPositionsAreIndexedAndMoved() {
        Map<String, JsonObject> saved = new HashMap<>();
        StallBook book = book(saved);
        UUID a = UUID.randomUUID();
        Stall one = book.create(a, "A", "One", 3), two = book.create(a, "A", "Two", 3);
        book.link(one, "w", 1, 2, 3, List.of("c"));
        assertSame(one, book.bySign("w", 1, 2, 3));
        book.link(one, "w", 4, 5, 6, List.of("c"));
        assertNull(book.bySign("w", 1, 2, 3));
        assertSame(one, book.bySign("w", 4, 5, 6));
        // a second stall on the same spot takes it over
        book.link(two, "w", 4, 5, 6, List.of("c"));
        assertSame(two, book.bySign("w", 4, 5, 6));
        assertFalse(one.signed);
        book.unlink(two);
        assertNull(book.bySign("w", 4, 5, 6));
        book.remove(one);
        assertNull(book.byName("One"));
        assertNull(book.byId(one.id));
        assertEquals(1, saved.size());
        assertEquals(List.of("Two"), book.names());
    }

    @Test public void loadingRefusesNameClashes() {
        StallBook book = book(new HashMap<>());
        UUID a = UUID.randomUUID();
        assertTrue(book.load(new Stall("s1", a, "A", "Same")));
        assertFalse(book.load(new Stall("s2", UUID.randomUUID(), "B", "same")));
        assertFalse(book.load(new Stall("s1", a, "A", "Other")));
        assertEquals(1, book.size());
    }

    @Test public void everyTextKeyUsedInCodeExistsInBothLanguages() throws Exception {
        TreeSet<String> zh = LangKeys.keys("/lang/zh-TW.yml"), en = LangKeys.keys("/lang/en-US.yml");
        assertEquals(zh, en);
        // keys the code builds from a prefix: every ending must exist
        for (String end : List.of("open", "closed", "locked", "land")) {
            assertTrue("stall.state_" + end, zh.contains("stall.state_" + end));
            assertTrue("stall.sign_" + end, zh.contains("stall.sign_" + end));
        }
        // names that look like text keys but are config keys
        java.util.Set<String> config = java.util.Set.of("stall.tax");
        Pattern key = Pattern.compile("\"((?:shop|window|help|log|stall)\\.[a-z0-9_]*[a-z0-9])\"");
        List<String> missing = new ArrayList<>();
        try (var files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                Matcher m = key.matcher(Files.readString(file, java.nio.charset.StandardCharsets.UTF_8));
                while (m.find()) if (!zh.contains(m.group(1)) && !config.contains(m.group(1))) missing.add(file.getFileName() + " " + m.group(1));
            }
        }
        assertTrue("missing keys: " + missing, missing.isEmpty());
    }

    @Test public void noChineseTextInJava() throws Exception {
        Pattern han = Pattern.compile("[\\u4e00-\\u9fff]");
        List<String> found = new ArrayList<>();
        try (var files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(f -> f.getFileName().toString().startsWith("Stall") || f.getFileName().toString().equals("LandLink.java") || f.getFileName().toString().equals("Stock.java")).toList())
                for (String text : Files.readAllLines(file, java.nio.charset.StandardCharsets.UTF_8))
                    if (!text.trim().startsWith("*") && !text.trim().startsWith("/") && han.matcher(text).find()) found.add(file.getFileName() + ": " + text.trim());
        }
        assertTrue("Chinese in code: " + found, found.isEmpty());
    }

    /** The flat keys of a language file */
    static final class LangKeys {
        static TreeSet<String> keys(String path) throws Exception {
            try (var in = LangKeys.class.getResourceAsStream(path)) {
                var out = new TreeSet<String>();
                flatten("", new org.yaml.snakeyaml.Yaml().load(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)), out);
                return out;
            }
        }

        private static void flatten(String prefix, Object node, java.util.Set<String> out) {
            if (node instanceof Map<?, ?> m) m.forEach((k, v) -> flatten(prefix.isEmpty() ? String.valueOf(k) : prefix + "." + k, v, out));
            else out.add(prefix);
        }
    }
}
