package org.example.service;

/**
 * 知识库中不存在指定文档。继承 IllegalArgumentException，便于既有调用方按参数错误处理。
 */
public class DocumentNotFoundException extends IllegalArgumentException {

    public DocumentNotFoundException(String message) {
        super(message);
    }
}
