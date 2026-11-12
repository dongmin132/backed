package dev.backed.investigate;

import java.util.ArrayList;
import java.util.List;

/** Splits text into plain and marked segments, marking every occurrence of the given quotes. */
public final class Highlighter {

    public record Segment(String text, boolean marked) {
    }

    private Highlighter() {
    }

    public static List<Segment> segments(String text, List<String> quotes) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        boolean[] mark = new boolean[text.length()];
        for (String q : quotes) {
            if (q == null || q.isBlank()) {
                continue;
            }
            int from = 0;
            int at;
            while ((at = text.indexOf(q, from)) >= 0) {
                for (int i = at; i < at + q.length(); i++) {
                    mark[i] = true;
                }
                from = at + q.length();
            }
        }
        List<Segment> out = new ArrayList<>();
        int start = 0;
        for (int i = 1; i <= text.length(); i++) {
            if (i == text.length() || mark[i] != mark[start]) {
                out.add(new Segment(text.substring(start, i), mark[start]));
                start = i;
            }
        }
        return out;
    }
}
