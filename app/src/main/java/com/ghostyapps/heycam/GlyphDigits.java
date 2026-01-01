package com.ghostyapps.heycam;

import java.util.HashMap;
import java.util.Map;

public class GlyphDigits {
    // 3 sütun x 5 satır LED haritası
    private static final Map<Integer, int[][]> DIGITS = new HashMap<>();

    static {
        DIGITS.put(0, new int[][]{{1,1,1}, {1,0,1}, {1,0,1}, {1,0,1}, {1,1,1}});
        DIGITS.put(1, new int[][]{{0,1,0}, {1,1,0}, {0,1,0}, {0,1,0}, {1,1,1}});
        DIGITS.put(2, new int[][]{{1,1,1}, {0,0,1}, {1,1,1}, {1,0,0}, {1,1,1}});
        DIGITS.put(3, new int[][]{{1,1,1}, {0,0,1}, {1,1,1}, {0,0,1}, {1,1,1}});
        DIGITS.put(4, new int[][]{{1,0,1}, {1,0,1}, {1,1,1}, {0,0,1}, {0,0,1}});
        DIGITS.put(5, new int[][]{{1,1,1}, {1,0,0}, {1,1,1}, {0,0,1}, {1,1,1}});
        DIGITS.put(6, new int[][]{{1,1,1}, {1,0,0}, {1,1,1}, {1,0,1}, {1,1,1}});
        DIGITS.put(7, new int[][]{{1,1,1}, {0,0,1}, {0,1,0}, {0,1,0}, {0,1,0}});
        DIGITS.put(8, new int[][]{{1,1,1}, {1,0,1}, {1,1,1}, {1,0,1}, {1,1,1}});
        DIGITS.put(9, new int[][]{{1,1,1}, {1,0,1}, {1,1,1}, {0,0,1}, {1,1,1}});
    }

    public static int[][] getMap(int number) {
        return DIGITS.getOrDefault(number, new int[][]{{0,0,0}, {0,1,0}, {0,0,0}, {0,0,0}, {0,0,0}});
    }
}