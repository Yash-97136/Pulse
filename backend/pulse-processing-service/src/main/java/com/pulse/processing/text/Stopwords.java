package com.pulse.processing.text;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.en.EnglishAnalyzer;

public final class Stopwords {

    private final Set<String> merged;

    private Stopwords(Set<String> merged) {
        this.merged = merged;
    }

    public static Stopwords load(Optional<String> classpathFile, Set<String> runtimeExtras) {
        Set<String> out = new HashSet<>();

        CharArraySet defaults = EnglishAnalyzer.getDefaultStopSet();
        for (Object token : defaults) {
            if (token instanceof char[] chars) {
                out.add(new String(chars));
            } else if (token != null) {
                out.add(token.toString());
            }
        }

        classpathFile.ifPresent(path -> {
            try (var in = Stopwords.class.getResourceAsStream(path)) {
                if (in != null) {
                    try (var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            line = line.strip();
                            if (!line.isEmpty() && !line.startsWith("#")) {
                                out.add(line.toLowerCase(Locale.ROOT));
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
        });

        if (runtimeExtras != null) {
            runtimeExtras.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(s -> s.toLowerCase(Locale.ROOT))
                .forEach(out::add);
        }

        return new Stopwords(out);
    }

    public boolean contains(String token) {
        return merged.contains(token);
    }

    public Set<String> asSet() {
        return Set.copyOf(merged);
    }
}