package com.zz.pgadskip;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class LaundryTransitionsTest {
    static int checks;
    static LaundryDevice d(String id, int state) { return new LaundryDevice(id, id, "test", state); }
    static List<LaundryDevice> rows(LaundryDevice... rows) { return Arrays.asList(rows); }
    static Set<String> selected(String... ids) { return new HashSet<>(Arrays.asList(ids)); }
    static void expect(int count, List<LaundryDevice> alerts, String why) {
        if (alerts.size() != count) throw new AssertionError(why + ": expected " + count + ", got " + alerts.size());
        checks++;
    }
    public static void main(String[] args) {
        LaundryTransitions t = new LaundryTransitions();
        Set<String> a = selected("a");
        expect(0, t.update(rows(d("a", 10)), a), "initial idle is a baseline");
        expect(0, t.update(rows(d("a", 10)), a), "repeated idle");
        expect(0, t.update(rows(d("a", 20)), a), "becomes busy");
        expect(1, t.update(rows(d("a", 10)), a), "busy to idle");
        expect(0, t.update(rows(d("a", 10)), a), "do not repeat notification");
        expect(0, t.update(rows(d("a", 20)), a), "new cycle");
        expect(0, t.update(rows(d("a", 999)), a), "unknown must not mean idle");
        expect(0, t.update(Collections.emptyList(), a), "missing device must not mean idle");
        expect(1, t.update(rows(d("a", 10)), a), "confirmed idle after temporary loss");
        t.update(rows(d("a", 20)), a);
        expect(0, t.update(rows(d("a", 30)), a), "fault must not alert");
        expect(0, t.update(rows(d("a", 10)), a), "fault to idle is not a completed wash");
        t.update(rows(d("a", 20)), a);
        expect(0, t.update(rows(d("a", 40)), a), "disabled must not alert");
        expect(0, t.update(rows(d("a", 10)), a), "disabled to idle is not a completed wash");
        t.update(rows(d("a", 20)), a);
        expect(0, t.update(rows(d("a", 10)), selected()), "deselected device");
        expect(0, t.update(rows(d("a", 10)), a), "reselect creates a new baseline");
        LaundryTransitions multi = new LaundryTransitions();
        Set<String> both = selected("a", "b");
        expect(0, multi.update(rows(d("a", 20), d("b", 20), d("c", 20)), both), "multiple baseline");
        expect(2, multi.update(rows(d("a", 10), d("b", 10), d("c", 10)), both), "only selected devices alert");
        expect(0, multi.update(rows(d("a", 10), d("b", 10)), both), "multiple alerts deduplicate");
        if (LaundryDevice.normalize(-1) != 0 || LaundryDevice.normalize(1) != 0 || LaundryDevice.normalize(2) != 0)
            throw new AssertionError("old API enum must not be interpreted as workStatus");
        checks++;
        System.out.println("PASS: " + checks + " transition and state-mapping checks");
    }
}
