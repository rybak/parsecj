package org.javafp.data;

import org.junit.Assert;
import org.junit.Test;

public class ListTest {

    @Test
    public void testEmpty() {
        final List<Integer> empty = List.empty();
        Assert.assertTrue("isEmpty should be true for empty list", empty.isEmpty());
        Assert.assertEquals("length should be 0 for empty list", 0, empty.length());
        Assert.assertEquals("[].toString()", "[]", empty.toString());
        Assert.assertEquals("foldl failed for empty list", 0, empty.foldl((x, y) -> x - y, 0).intValue());
        Assert.assertEquals("foldr failed for empty list", 0, empty.foldr((x, y) -> x - y, 0).intValue());
        Assert.assertEquals("map failed for empty list", List.empty(), empty.map(x -> null));
        Assert.assertTrue("match failed for empty list", empty.match(n -> false, e -> true));
    }

    @Test
    public void testNonEmpty() {
        final List<Integer> l = List.of(1, 2, 3, 4);
        Assert.assertFalse("[isEmpty() should return false for a non-empty list", l.isEmpty());
        Assert.assertEquals("[1,2,3,4].length should be 4 for empty list", 4, l.length());
        Assert.assertEquals("[1,2,3,4].toString()", "[1,2,3,4]", l.toString());
        Assert.assertEquals("foldl failed for non-empty list", (((10-1)-2)-3)-4, l.foldl((x, y) -> x - y, 10).intValue());
        Assert.assertEquals("foldr failed for non-empty list", 1-(2-(3-(4-10))), l.foldr((x, y) -> x - y, 10).intValue());
        Assert.assertEquals("map failed for non-empty list", List.of(-1, -2, -3, -4), l.map(i -> -i));
        Assert.assertTrue("match failed for non-empty list", l.match(n -> true, e -> false));
    }
}