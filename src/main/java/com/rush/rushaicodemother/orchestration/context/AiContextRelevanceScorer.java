package com.rush.rushaicodemother.orchestration.context;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Unicode 感知的词汇相关性用于对短期跟踪（包括中文文本）进行重新排序。 */
@Component
public class AiContextRelevanceScorer {

    private static final int MAX_INPUT_CHARS = 4_000;
    private static final double RELATED_THRESHOLD = 0.18;

    /**
 * 返回{@code score}。
 *
 * @param query 查询
 * @param candidate 候选
 * @return 计算或处理后的数值结果
 */
    public double score(String query, String candidate) {
        String normalizedQuery = normalize(query);
        String normalizedCandidate = normalize(candidate);
        if (normalizedQuery.isBlank() || normalizedCandidate.isBlank()) {
            return 0.0;
        }

        Set<String> queryTerms = terms(normalizedQuery);
        Set<String> candidateTerms = terms(normalizedCandidate);
        if (queryTerms.isEmpty() || candidateTerms.isEmpty()) {
            return 0.0;
        }
        int intersection = 0;
        // 按既定顺序逐项处理，并在达到资源或状态边界时提前结束。
        for (String term : queryTerms) {
            if (candidateTerms.contains(term)) {
                intersection++;
            }
        }
        double coverage = intersection / (double) queryTerms.size();
        double jaccard = intersection
                / (double) Math.max(1, queryTerms.size() + candidateTerms.size() - intersection);
        double score = coverage * 0.75 + jaccard * 0.25;

        String compactQuery = compact(normalizedQuery);
        String compactCandidate = compact(normalizedCandidate);
        if (Math.min(compactQuery.length(), compactCandidate.length()) >= 4
                && (compactQuery.contains(compactCandidate)
                || compactCandidate.contains(compactQuery))) {
            score = Math.max(score, 0.85);
        }
        return Math.max(0.0, Math.min(1.0, score));
    }

    public boolean related(String query, String candidate) {
        return score(query, candidate) >= RELATED_THRESHOLD;
    }

    /** 返回{@code terms}。 */
    private Set<String> terms(String text) {
        Set<String> terms = new HashSet<>();
        StringBuilder latin = new StringBuilder();
        StringBuilder cjk = new StringBuilder();
        text.codePoints().forEach(codePoint -> {
            if (isCjk(codePoint)) {
                flushLatin(terms, latin);
                cjk.appendCodePoint(codePoint);
                return;
            }
            flushCjk(terms, cjk);
            if (Character.isLetterOrDigit(codePoint) || codePoint == '_' || codePoint == '-') {
                latin.appendCodePoint(codePoint);
            } else {
                flushLatin(terms, latin);
            }
        });
        flushLatin(terms, latin);
        flushCjk(terms, cjk);
        return terms;
    }

    private void flushLatin(Set<String> terms, StringBuilder token) {
        if (token.length() >= 2) {
            terms.add(token.toString());
        }
        token.setLength(0);
    }

    /** 处理{@code flush}{@code Cjk}。 */
    private void flushCjk(Set<String> terms, StringBuilder run) {
        if (run.isEmpty()) {
            return;
        }
        int[] codePoints = run.toString().codePoints().toArray();
        if (codePoints.length >= 2 && codePoints.length <= 12) {
            terms.add(new String(codePoints, 0, codePoints.length));
        }
        for (int index = 0; index + 1 < codePoints.length; index++) {
            terms.add(new String(codePoints, index, 2));
        }
        for (int index = 0; index + 2 < codePoints.length; index++) {
            terms.add(new String(codePoints, index, 3));
        }
        run.setLength(0);
    }

    private boolean isCjk(int codePoint) {
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL;
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String bounded = value.length() <= MAX_INPUT_CHARS
                ? value
                : value.substring(0, MAX_INPUT_CHARS);
        return Normalizer.normalize(bounded, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .trim();
    }

    private String compact(String value) {
        return value.replaceAll("[^\\p{L}\\p{N}]", "");
    }
}
