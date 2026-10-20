package dev.themisstats;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the mixin package.
 *
 * <p>Every class in the package that the mixin configuration owns is a mixin class, and Mixin
 * refuses to load a class from such a package directly
 * ({@code IllegalClassLoadError: ... is in a defined mixin package ... and cannot be referenced
 * directly}). A plain helper class in the mixin package therefore does not fail at build time -
 * it crashes the server the first time it is used.</p>
 */
class MixinPackageTest {

    private static final Path MIXIN_DIR = Path.of("src", "main", "java", "dev", "themisstats", "mixin");

    private static final Path MIXIN_CONFIG = Path.of("src", "main", "resources", "themisstats.mixins.json");

    @Test
    void everyClassInTheMixinPackageIsRegisteredAsAMixin() throws IOException {
        Set<String> registered = registeredMixins();
        assertTrue(!registered.isEmpty(), "mixins.json 里没有解析到任何 Mixin：" + MIXIN_CONFIG);

        List<String> unregistered = new ArrayList<>();
        try (var stream = Files.list(MIXIN_DIR)) {
            for (Path file : stream.filter(p -> p.getFileName().toString().endsWith(".java")).toList()) {
                String name = file.getFileName().toString().replace(".java", "");
                if (!registered.contains(name)) {
                    unregistered.add(name);
                }
            }
        }
        assertTrue(unregistered.isEmpty(),
                "这些类在 Mixin 包里却没有登记进 themisstats.mixins.json，运行时会直接崩服："
                        + unregistered);
    }

    @Test
    void everyRegisteredMixinHasASourceFile() throws IOException {
        Set<String> registered = registeredMixins();
        List<String> missing = new ArrayList<>();
        for (String name : registered) {
            if (!Files.exists(MIXIN_DIR.resolve(name + ".java"))) {
                missing.add(name);
            }
        }
        assertTrue(missing.isEmpty(), "mixins.json 里登记了不存在的类：" + missing);
    }

    /**
     * @return the simple class names of the mixins that are registered in the configuration
     */
    private static Set<String> registeredMixins() throws IOException {
        String json = Files.readString(MIXIN_CONFIG, StandardCharsets.UTF_8);
        Matcher array = Pattern.compile("\"mixins\"\\s*:\\s*\\[(.*?)]", Pattern.DOTALL).matcher(json);
        Set<String> names = new LinkedHashSet<>();
        if (!array.find()) {
            return names;
        }
        Matcher entry = Pattern.compile("\"([A-Za-z0-9_]+)\"").matcher(array.group(1));
        while (entry.find()) {
            names.add(entry.group(1));
        }
        return names;
    }
}
