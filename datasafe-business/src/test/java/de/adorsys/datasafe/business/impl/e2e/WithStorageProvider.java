package de.adorsys.datasafe.business.impl.e2e;

import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.google.common.base.Suppliers;
import de.adorsys.datasafe.storage.api.StorageService;
import de.adorsys.datasafe.storage.impl.fs.FileSystemStorageService;
import de.adorsys.datasafe.storage.impl.s3.S3StorageService;
import de.adorsys.datasafe.types.api.resource.Uri;
import de.adorsys.datasafe.types.api.shared.BaseMockitoTest;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.platform.commons.util.StringUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.shaded.org.apache.commons.io.FileUtils;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import java.util.stream.Stream;


/**
 * Provides different storage types - filesystem, minio, etc. to be used in tests.
 */
@Slf4j
@Getter
public abstract class WithStorageProvider extends BaseMockitoTest {

    private static String deeperBucketPath = "deeper/and/deeper";

    private static final ExecutorService EXECUTOR_SERVICE = Executors.newFixedThreadPool(5);
    private static String minioAccessKeyID = "admin";
    private static String minioSecretAccessKey = "password";
    private static String minioRegion = "eu-central-1";
    private static String minioBucketName = "home";
    private static String minioUrl = "http://localhost";
    private static String minioMappedUrl;

    private static String cephAccessKeyID = "admin";
    private static String cephSecretAccessKey = "password";
    private static String cephRegion = "eu-central-1";
    private static String cephBucketName = "home";
    private static String cephUrl = "http://localhost";
    private static String cephMappedUrl;

    private static String amazonAccessKeyID = System.getProperty("AWS_ACCESS_KEY");
    private static String amazonSecretAccessKey = System.getProperty("AWS_SECRET_KEY");
    private static String amazonRegion = System.getProperty("AWS_REGION", "eu-central-1");
    private static String amazonBucket = System.getProperty("AWS_BUCKET", "adorsys-docusafe");
    private static String amazonMappedUrl;

    private static GenericContainer minioContainer;
    private static GenericContainer cephContainer;

    private static Path tempDir;
    private static AmazonS3 minio;
    private static AmazonS3 ceph;
    private static AmazonS3 amazonS3;

    private static Supplier<Void> cephStorage;
    private static Supplier<Void> minioStorage;
    private static Supplier<Void> amazonSotrage;

    @BeforeAll
    static void init(@TempDir Path tempDir) {
        log.info("Executing init");
        // TODO fixme
        log.info(""); // for some strange reason, the newline of the previous statement is gone
        WithStorageProvider.tempDir = tempDir;

        minioStorage = Suppliers.memoize(() -> {
            startMinio();
            return null;
        });

        cephStorage = Suppliers.memoize(() -> {
            startCeph();
            return null;
        });

        amazonSotrage = Suppliers.memoize(() -> {
            initS3();
            return null;
        });
    }

    @AfterEach
    @SneakyThrows
    void cleanup() {
        log.info("Executing cleanup");
        if (null != tempDir && tempDir.toFile().exists()) {
            FileUtils.cleanDirectory(tempDir.toFile());
        }

        if (null != minio) {
            removeObjectFromS3(minio, minioBucketName, deeperBucketPath);
        }

        if (null != amazonS3) {
            removeObjectFromS3(amazonS3, amazonBucket, deeperBucketPath);
        }
    }

    @AfterAll
    static void shutdown() {
        log.info("Stopping containers");
        if (null != minioContainer) {
            minioContainer.stop();
            minioContainer = null;
            minio = null;
        }

        if (null != cephContainer) {
            cephContainer.stop();
            cephContainer = null;
            ceph = null;
        }

        amazonS3 = null;
    }

    @ValueSource
    protected static Stream<WithStorageProvider.StorageDescriptor> allStorages() {
        return Stream.of(
                fs(),
                minio(),
                s3()
        ).filter(Objects::nonNull);
    }

    @ValueSource
    protected static Stream<WithStorageProvider.StorageDescriptor> minioStorage() {
        return Stream.of(
                minio()
        ).filter(Objects::nonNull);
    }
    @ValueSource
    protected static Stream<WithStorageProvider.StorageDescriptor> fsStorage() {
        return Stream.of(
                fs()
        ).filter(Objects::nonNull);
    }

    private static WithStorageProvider.StorageDescriptor fs() {
        return new StorageDescriptor(
                StorageDescriptorName.FILESYSTEM,
                () -> new FileSystemStorageService(new Uri(tempDir.toUri())),
                new Uri(tempDir.toUri()),
                null, null, null,
                tempDir.toString()
        );
    }

    protected static StorageDescriptor minio() {
        return new StorageDescriptor(
                StorageDescriptorName.MINIO,
                () -> {
                    minioStorage.get();
                    return new S3StorageService(minio, minioBucketName, EXECUTOR_SERVICE);
                },
                new Uri("s3://" + minioBucketName + "/" + deeperBucketPath + "/"),
                minioAccessKeyID,
                minioSecretAccessKey,
                minioRegion,
                minioBucketName + "/" + deeperBucketPath
        );
    }

    protected static StorageDescriptor ceph() {
        return new StorageDescriptor(
                StorageDescriptorName.CEPH,
                () -> {
                    cephStorage.get();
                    return new S3StorageService(ceph, cephBucketName, EXECUTOR_SERVICE);
                },
                new Uri("s3://" + cephBucketName + "/" + deeperBucketPath + "/"),
                cephAccessKeyID,
                cephSecretAccessKey,
                cephRegion,
                cephBucketName  + "/" + deeperBucketPath
        );
    }

    protected static StorageDescriptor s3() {
        if (null == amazonAccessKeyID) {
            return null;
        }

        return new StorageDescriptor(
                StorageDescriptorName.AMAZON,
                () -> {
                    amazonSotrage.get();
                    return new S3StorageService(amazonS3, amazonBucket, EXECUTOR_SERVICE);
                },
                new Uri("s3://" + amazonBucket + "/" + deeperBucketPath + "/"),
                amazonAccessKeyID,
                amazonSecretAccessKey,
                amazonRegion,
                amazonBucket + "/" + deeperBucketPath
        );
    }

    private void removeObjectFromS3(AmazonS3 amazonS3, String bucket, String prefix) {
        amazonS3.listObjects(bucket, prefix)
                .getObjectSummaries()
                .forEach(it -> {
                    log.debug("Remove {}", it.getKey());
                    amazonS3.deleteObject(bucket, it.getKey());
                });
    }

    private static void initS3() {
        log.info("Initializing S3");
        if (StringUtils.isBlank(amazonAccessKeyID)) {
            return;
        }

                amazonS3 = AmazonS3ClientBuilder.standard()
                .withCredentials(new AWSStaticCredentialsProvider(
                        new BasicAWSCredentials(amazonAccessKeyID, amazonSecretAccessKey))
                )
                .withRegion(amazonRegion)
                .build();

        amazonMappedUrl = "s3://" + amazonBucket + "/" + deeperBucketPath + "/";
        log.info("Amazon napped URL:" + amazonMappedUrl);
    }

    private static void startMinio() {
        log.info("Starting MINIO");
        minioContainer = new GenericContainer("minio/minio")
                .withExposedPorts(9000)
                .withEnv("MINIO_ACCESS_KEY", minioAccessKeyID)
                .withEnv("MINIO_SECRET_KEY", minioSecretAccessKey)
                .withCommand("server /data")
                .waitingFor(Wait.defaultWaitStrategy());

        minioContainer.start();
        Integer mappedPort = minioContainer.getMappedPort(9000);
        minioMappedUrl = minioUrl + ":" + mappedPort;
        log.info("Minio mapped URL:" + minioMappedUrl);
        minio = AmazonS3ClientBuilder.standard()
                .withEndpointConfiguration(
                        new AwsClientBuilder.EndpointConfiguration(minioMappedUrl, minioRegion)
                )
                .withCredentials(
                        new AWSStaticCredentialsProvider(
                                new BasicAWSCredentials(minioAccessKeyID, minioSecretAccessKey)
                        )
                )
                .enablePathStyleAccess()
                .build();


        minio.createBucket(minioBucketName);
    }

    private static void startCeph() {
        log.info("Starting CEPH");
        cephContainer = new GenericContainer("localrepo/ceph-nano")
                .withExposedPorts(5000)
                .withEnv("MON_IP", "0.0.0.0")
                .withEnv("CEPH_PUBLIC_NETWORK", "0.0.0.0/0")
                .withEnv("CEPH_DEMO_UID", "ceph")
                .withEnv("CEPH_DEMO_ACCESS_KEY", cephAccessKeyID)
                .withEnv("CEPH_DEMO_SECRET_KEY", cephSecretAccessKey)
                .withEnv("CEPH_DEMO_BUCKET", cephBucketName)
                .waitingFor(Wait.defaultWaitStrategy());

        cephContainer.start();
        Integer mappedPort = cephContainer.getMappedPort(9000);
        log.info("Ceph mapped URL:" + cephMappedUrl);
        cephMappedUrl = cephUrl + ":" + mappedPort;
        ceph = AmazonS3ClientBuilder.standard()
                .withEndpointConfiguration(
                        new AwsClientBuilder.EndpointConfiguration(cephMappedUrl, cephRegion)
                )
                .withCredentials(
                        new AWSStaticCredentialsProvider(
                                new BasicAWSCredentials(cephAccessKeyID, cephSecretAccessKey)
                        )
                )
                .enablePathStyleAccess()
                .build();


        ceph.createBucket(cephBucketName);
    }

    @Getter
    @ToString(of = "name")
    @AllArgsConstructor
    public static class StorageDescriptor {

        private final StorageDescriptorName name;
        private final Supplier<StorageService> storageService;
        private final Uri location;
        private final String accessKey;
        private final String secretKey;
        private final String region;
        private final String rootBucket;

        public String getMappedUrl() {
            switch(name) {
                case MINIO: return minioMappedUrl;
                case CEPH: return cephMappedUrl;
                case AMAZON: return amazonMappedUrl;
                case FILESYSTEM: return null;
                default: throw new RuntimeException("missing switch for " + name);
            }
        }
    }

    public enum StorageDescriptorName {
        FILESYSTEM,
        MINIO,
        CEPH,
        AMAZON
    }
}
