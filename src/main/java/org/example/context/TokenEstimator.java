package org.example.context;

import org.springframework.stereotype.Component;

/**
 * A conservative tokenizer-independent estimate. CJK characters are counted as
 * one token and latin text as roughly one token per four characters.
 */
@Component
public class TokenEstimator {

    public int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }

        int cjk = 0;
        int other = 0;
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (isCjk(codePoint)) {
                cjk++;
            } else {
                other++;
            }
        }
        return cjk + (other + 3) / 4 + 4;
    }

    public String truncate(String text, int maxTokens) {
        if (text == null || maxTokens <= 0) {
            return "";
        }
        if (estimate(text) <= maxTokens) {
            return text;
        }

        int low = 0;
        int high = text.length();
        while (low < high) {
            int middle = (low + high + 1) >>> 1;
            if (estimate(text.substring(0, middle)) <= maxTokens) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        return text.substring(0, low) + "\n[摘要因上下文预算被截断]";
    }

    private boolean isCjk(int codePoint) {
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL;
    }
}
