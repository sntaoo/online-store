package com.example.onlinestore.service;

/**
 * @Author: sntaoo
 * @Date: 2025/4/18 16:16
 * @Description: 评论区域
 */
public enum CommentZoneEnum {
    RANDOM_INCREASE(1),
    RANDOM_DECREASE(2),
    HASHCODE_INCREASE(3),
    HASHCODE_DECREASE(4);
    private int value;
    CommentZoneEnum(int value) {
        this.value = value;
    }
    public int getValue() {
        return value;
    }
}
