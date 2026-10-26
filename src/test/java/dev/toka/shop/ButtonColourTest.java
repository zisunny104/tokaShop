package dev.toka.shop;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertTrue;

/** Button labels carry no colour code, except a leading red one on close/cancel. */
public class ButtonColourTest {
    private static final String[] BUTTON_KEYS = {"window.search", "window.prev", "window.next", "window.back", "window.close", "window.admin",
            "window.reload", "window.confirm", "window.cancel", "stall.browse", "stall.my_title", "stall.create", "stall.add_stock",
            "stall.do_open", "stall.do_close", "stall.rename", "stall.delete", "stall.products", "stall.line_button",
            "stall.browse_line", "stall.my_line"};

    @Test
    @SuppressWarnings("unchecked")
    public void buttonLabelsHaveNoColour() throws IOException {
        for (String lang : new String[]{"zh-TW.yml", "en-US.yml"})
        try (var in = Files.newBufferedReader(Path.of("src", "main", "resources", "lang", lang), StandardCharsets.UTF_8)) {
            Object root = new org.yaml.snakeyaml.Yaml().load(in);
            for (String key : BUTTON_KEYS) {
                Object value = root;
                for (String part : key.split("\\.")) value = ((Map<String, Object>) value).get(part);
                String text = String.valueOf(value);
                String rest = text.startsWith("§c") ? text.substring(2) : text;
                assertTrue(lang + " " + key + " = " + text, !rest.contains("§"));
            }
        }
    }
}
