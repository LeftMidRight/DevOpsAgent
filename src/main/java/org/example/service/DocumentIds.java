package org.example.service;

import java.util.UUID;

/**
 * 把接口传入的文档 ID 解析为 UUID。非法值统一视为参数错误。
 */
final class DocumentIds {

    private DocumentIds() {
    }

    static UUID parse(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException("非法文档ID");
        }
    }
}
