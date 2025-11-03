package com.pulse.processing.text;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class Tokenizer {
    private final Stopwords stopwords;
    private final int minLen;
    private final int maxLen;

    public Tokenizer(Stopwords stopwords, int minLen, int maxLen) {
        this.stopwords = stopwords;
        this.minLen = minLen;
        this.maxLen = maxLen;
    }

    public List<String> tokens(String text) {
        if (text == null || text.isBlank()) return List.of();
        String norm = Normalizer.normalize(text, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT);

        // keep letters and spaces; turn everything else into space
        norm = norm.replaceAll("[^a-z]+", " ").trim();

        String[] parts = norm.split("\\s+");
        List<String> out = new ArrayList<>(parts.length);
        for (String p : parts) {
            if (p.length() < minLen || p.length() > maxLen) continue;
            if (stopwords.contains(p)) continue;
            out.add(p);
        }
        return out;
    }
}