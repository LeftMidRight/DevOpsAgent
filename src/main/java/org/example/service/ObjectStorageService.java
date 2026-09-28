package org.example.service;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.errors.ErrorResponseException;
import jakarta.annotation.PostConstruct;
import org.example.config.MinioProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.InputStream;

/**
 * 对象存储访问入口：文档原文的唯一存储位置（MinIO）。
 * 启动时确保 bucket 存在，配置不可用时快速失败。
 */
@Service
public class ObjectStorageService {

    private static final Logger logger = LoggerFactory.getLogger(ObjectStorageService.class);

    private final MinioClient minioClient;
    private final MinioProperties properties;

    public ObjectStorageService(MinioClient minioClient, MinioProperties properties) {
        this.minioClient = minioClient;
        this.properties = properties;
    }

    @PostConstruct
    public void ensureBucket() {
        try {
            String bucket = properties.getBucket();
            boolean exists = minioClient.bucketExists(
                    BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                logger.info("已创建 MinIO bucket: {}", bucket);
            }
            logger.info("MinIO 对象存储初始化完成 - endpoint: {}, bucket: {}",
                    properties.getEndpoint(), bucket);
        } catch (Exception e) {
            throw new IllegalStateException("MinIO 初始化失败: " + e.getMessage(), e);
        }
    }

    public void putObject(String objectKey, InputStream content, long size, String contentType) {
        try {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(properties.getBucket())
                    .object(objectKey)
                    .stream(content, size, -1)
                    .contentType(contentType)
                    .build());
        } catch (Exception e) {
            throw new RuntimeException("写入对象存储失败: " + objectKey + " - " + e.getMessage(), e);
        }
    }

    public byte[] getObject(String objectKey) {
        try (InputStream in = minioClient.getObject(GetObjectArgs.builder()
                .bucket(properties.getBucket())
                .object(objectKey)
                .build())) {
            return in.readAllBytes();
        } catch (ErrorResponseException e) {
            if ("NoSuchKey".equals(e.errorResponse().code())) {
                throw new IllegalStateException("对象存储中不存在: " + objectKey);
            }
            throw new RuntimeException("读取对象存储失败: " + objectKey + " - " + e.getMessage(), e);
        } catch (Exception e) {
            throw new RuntimeException("读取对象存储失败: " + objectKey + " - " + e.getMessage(), e);
        }
    }

    public void removeObject(String objectKey) {
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(properties.getBucket())
                    .object(objectKey)
                    .build());
        } catch (Exception e) {
            logger.warn("删除对象存储文件失败: {} - {}", objectKey, e.getMessage());
        }
    }
}