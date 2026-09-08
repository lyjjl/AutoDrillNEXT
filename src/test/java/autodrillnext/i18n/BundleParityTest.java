package autodrillnext.i18n;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BundleParityTest {
    @Test
    void englishAndSimplifiedChineseBundlesHaveTheSameKeys() throws IOException {
        Set<String> english = keys(Path.of("assets/bundles/bundle.properties"));
        Set<String> chinese = keys(Path.of("assets/bundles/bundle_zh_CN.properties"));

        assertEquals(english, chinese, () -> "bundle key mismatch: missing in zh-CN="
            + difference(english, chinese) + ", missing in English=" + difference(chinese, english));
    }

    private static Set<String> keys(Path path) throws IOException {
        return Files.readAllLines(path).stream()
            .map(String::trim)
            .filter(line -> !line.isEmpty() && !line.startsWith("#") && !line.startsWith("!"))
            .map(line -> line.split("[=:]", 2)[0].trim())
            .collect(Collectors.toCollection(TreeSet::new));
    }

    private static Set<String> difference(Set<String> left, Set<String> right) {
        TreeSet<String> difference = new TreeSet<>(left);
        difference.removeAll(right);
        return difference;
    }
}
