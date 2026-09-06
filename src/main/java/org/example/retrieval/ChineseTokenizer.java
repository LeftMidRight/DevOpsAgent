package org.example.retrieval;

import com.huaban.analysis.jieba.JiebaSegmenter;
import com.huaban.analysis.jieba.WordDictionary;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ChineseTokenizer {

    private static final Set<String> STOP = Set.of("的", "了", "和", "或", "以及", "如何", "怎么");
    private static final Pattern IDENT = Pattern.compile("[A-Za-z][A-Za-z0-9_\\-]*");

    private final JiebaSegmenter segmenter = new JiebaSegmenter();

    public ChineseTokenizer() {
        try (var in = ChineseTokenizer.class.getResourceAsStream("/retrieval/ops-lexicon.txt")) {
            if (in == null) {
                return;
            }
            Path tmp = Files.createTempFile("ops-lexicon", ".txt");
            Files.write(tmp, new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .lines()
                    .toList());
            WordDictionary.getInstance().loadUserDict(tmp);
            Files.deleteIfExists(tmp);
        } catch (Exception ignored) {
            // lexicon is best-effort; BM25 still works on jieba defaults
        }
    }

    public List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String raw : segmenter.sentenceProcess(text)) {
            String t = raw.trim();
            if (t.isEmpty() || STOP.contains(t)) {
                continue;
            }
            if (t.chars().allMatch(c -> c < 128 && Character.isLetterOrDigit(c) || c == '_' || c == '-')) {
                t = t.toLowerCase(Locale.ROOT);
            }
            out.add(t);
        }
        Matcher m = IDENT.matcher(text);
        while (m.find()) {
            String ident = m.group().toLowerCase(Locale.ROOT);
            if (!out.contains(ident)) {
                out.add(ident);
            }
        }
        return out;
    }
}
