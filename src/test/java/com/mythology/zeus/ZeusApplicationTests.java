package com.mythology.zeus;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

@SpringBootTest
class ZeusApplicationTests {

    /**
     * Input: nums1 = [[1,2],[2,3],[4,5]], nums2 = [[1,4],[3,2],[4,1]]
     * Output: [[1,6],[2,3],[3,2],[4,6]]
     * Explanation: The resulting array contains the following:
     * - id = 1, the value of this id is 2 + 4 = 6.
     * - id = 2, the value of this id is 3.
     * - id = 3, the value of this id is 2.
     * - id = 4, the value of this id is 5 + 1 = 6
     */
    @Test
    void contextLoads() {
        int[][] nums1 = {{1, 2}, {2, 3}, {4, 5}};
        int[][] nums2 = {{1, 4}, {3, 2}, {4, 1}};
        int[][] num3 = new int[nums2.length + nums1.length][2];
        for (int i = 0; i < nums1.length; i++) {
            for (int i1 = 0; i1 < nums2.length; i1++) {
                if (nums2[i1][0] == nums1[i][0]) {
                    num3[nums2[i1][0] - 1] = new int[]{nums2[i1][0], nums2[i1][1] + nums1[i][1]};
                } else {
                    num3[nums2[i1][0] - 1] = new int[]{nums2[i1][0], nums2[i1][1]};
                }
//                if (nums1[i][0] == nums2[i1][0]) {
//                    num3[nums1[i][0] - 1] = new int[]{nums1[i][0], nums1[i1][1] + nums2[i1][1]};
//                } else {
//                    num3[nums1[i][0] - 1] = new int[]{nums1[i][0], nums2[i1][1]};
//                }
            }
        }
        for (int i = 0; i < num3.length; i++) {
            if (num3[i][0] == 0) {
                int[][] ints = new int[i][2];
                for (int i1 = 0; i1 < ints.length; i1++) {
                    ints[i1][0] = num3[i1][0];
                    ints[i1][1] = num3[i1][1];
                }
                System.out.println(ints);
            }
        }
        System.out.println(num3);
    }

    @Test
    void name() {
        int[][] nums1 = {{4, 5}, {1, 2}, {2, 3}};
        int[][] nums2 = {{1, 4}, {4, 1}, {3, 2}};
        HashMap<Integer, Integer> integerIntegerHashMap = new HashMap<>();
        for (int[] ints : nums1) {
            integerIntegerHashMap.put(ints[0], integerIntegerHashMap.getOrDefault(ints[0], 0) + ints[1]);
        }
        for (int[] ints : nums2) {
            integerIntegerHashMap.put(ints[0], integerIntegerHashMap.getOrDefault(ints[0], 0) + ints[1]);
        }
        ArrayList<int[]> arrayList = new ArrayList<>();
        for (Map.Entry<Integer, Integer> entry : integerIntegerHashMap.entrySet()) {
            arrayList.add(new int[]{entry.getKey(), entry.getValue()});
        }

        int[][] ints = new int[arrayList.size()][2];
        for (int i = 0; i < arrayList.size(); i++) {
            ints[i] = arrayList.get(i);
        }
        System.out.println(ints);
    }
}

