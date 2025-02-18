package de.adorsys.datasafe.business.impl.e2e;

import com.google.common.io.ByteStreams;
import de.adorsys.datasafe.business.impl.e2e.metrtics.TestMetricCollector;
import de.adorsys.datasafe.business.impl.service.DefaultDatasafeServices;
import de.adorsys.datasafe.encrypiton.api.types.UserIDAuth;
import de.adorsys.datasafe.storage.api.StorageService;
import de.adorsys.datasafe.types.api.actions.ReadRequest;
import de.adorsys.datasafe.types.api.actions.WriteRequest;
import de.adorsys.datasafe.types.api.resource.AbsoluteLocation;
import de.adorsys.datasafe.types.api.resource.ResolvedResource;
import de.adorsys.datasafe.types.api.resource.Uri;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.util.encoders.Hex;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.*;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static de.adorsys.datasafe.types.api.actions.ListRequest.forDefaultPrivate;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.apache.commons.compress.utils.IOUtils.closeQuietly;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Multithreaded test of basic operations.
 */
@Slf4j
class BasicFunctionalityWithConcurrencyTest extends BaseE2ETest {

    private static final int TIMEOUT_S = 10;

    private static int NUMBER_OF_TEST_USERS = 3;
    private static int NUMBER_OF_TEST_FILES = 5;
    private static int EXPECTED_NUMBER_OF_FILES_PER_USER = NUMBER_OF_TEST_FILES;

    private static final String TEST_FILENAME = "/test.txt";

    @TempDir
    protected Path tempTestFileFolder;
    protected StorageService storage;
    protected Uri location;

    private TestMetricCollector metricCollector = new TestMetricCollector();

    private Function<Runnable, Long> measurePerformanceAndReturnValue = (measuredMethod) -> {
        Instant start = Instant.now();

        measuredMethod.run();

        long durationOfSavingFile = Duration.between(start, Instant.now()).toMillis();

        return durationOfSavingFile;
    };

    private BiFunction<Supplier<UserIDAuth>, BiConsumer<String, Long>, UserIDAuth> measurePerformance = (measuredMethod, collector) -> {
        Instant start = Instant.now();

        UserIDAuth user = measuredMethod.get();

        long durationOfUserRegistration = Duration.between(start, Instant.now()).toMillis();

        collector.accept(user.getUserID().getValue(), durationOfUserRegistration);

        log.debug("Registered user: {} in {}ms", user.getUserID().getValue(), durationOfUserRegistration);

        return user;
    };

    @SneakyThrows
    @ParameterizedTest(name = "Run #{index} service storage: {0} with data size: {1} bytes and {2} threads.")
    @MethodSource("differentThreadsTestOptions")
    void writeToPrivateListPrivateInDifferentThreads(WithStorageProvider.StorageDescriptor descriptor, int size, int poolSize) {
        init(descriptor);

        String testFile = tempTestFileFolder.toString() + TEST_FILENAME;
        generateTestFile(testFile, size);

        ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(poolSize);

        CountDownLatch holdingLatch = new CountDownLatch(1);
        CountDownLatch finishHoldingLatch = new CountDownLatch(NUMBER_OF_TEST_USERS * NUMBER_OF_TEST_FILES);

        String checksumOfOriginTestFile;
        try(FileInputStream input = new FileInputStream(new File(testFile))) {
            checksumOfOriginTestFile = checksum(input);
        }

        log.trace("*** Starting write threads ***");
        for (int i = 0; i < NUMBER_OF_TEST_USERS; i++) {

            String userName = "john_" + i;

            executor.execute(() -> {
                UserIDAuth user = measurePerformance.apply(
                        () -> registerUser(userName),
                        metricCollector::addRegisterRecords
                );

                createFileForUserParallelly(executor, holdingLatch, finishHoldingLatch,
                        testFile, user);
            });
        }
        // open latch and start all threads
        holdingLatch.countDown();

        log.trace("*** Main thread waiting for all threads ***");
        finishHoldingLatch.await(TIMEOUT_S, SECONDS);
        executor.shutdown();
        log.trace("*** All threads are finished work ***");

        log.trace("*** Starting read info saved earlier *** ");
        for (int i = 0; i < NUMBER_OF_TEST_USERS; i++) {
            UserIDAuth user = createJohnTestUser(i);

            await().atMost(5, SECONDS).until(
                    () -> listAllPrivateFiles(user).size() == EXPECTED_NUMBER_OF_FILES_PER_USER
            );
            List<AbsoluteLocation<ResolvedResource>> resourceList = listAllPrivateFiles(user);
            assertThat(resourceList.size()).isEqualTo(EXPECTED_NUMBER_OF_FILES_PER_USER);
            resourceList.forEach(
                    item -> assertEquals(checksumOfOriginTestFile, calculateDecryptedContentChecksum(user, item)));
        }

        metricCollector.setDataSize(size);
        metricCollector.setStorageType(storage.getClass().getSimpleName());
        metricCollector.setNumberOfThreads(poolSize);
        metricCollector.writeToJSON();//json files in target folder
    }

    private List<AbsoluteLocation<ResolvedResource>> listAllPrivateFiles(UserIDAuth user) {
        return listPrivate.list(
                forDefaultPrivate(user, "./")).collect(Collectors.toList());
    }

    private void createFileForUserParallelly(ThreadPoolExecutor executor, CountDownLatch holdingLatch,
                                             CountDownLatch finishHoldingLatch, String testFilePath,
                                             UserIDAuth user) {
        AtomicInteger counter = new AtomicInteger();
        String remotePath = "folder2";

        for (int j = 0; j < NUMBER_OF_TEST_FILES; j++) {
            executor.execute(() -> {
                try {
                    holdingLatch.await();

                    Thread.currentThread().setName(user.getUserID().getValue());

                    String filePath = remotePath + "/" + counter.incrementAndGet() + ".txt";

                    log.debug("Saving file: {}", filePath);

                    long durationOfSavingFile = measurePerformanceAndReturnValue.apply(
                            () -> writeDataToFileForUser(user, filePath, testFilePath, finishHoldingLatch)
                    );

                    metricCollector.addSaveRecord(
                           user.getUserID().getValue(),
                           durationOfSavingFile
                    );

                    log.debug("Save file in {} ms", durationOfSavingFile);
                } catch (InterruptedException e) {
                    fail(e);
                }
            });
        }
    }

    private String calculateDecryptedContentChecksum(UserIDAuth user,
                                                     AbsoluteLocation<ResolvedResource> item) {
        try {
            InputStream decryptedFileStream = readFromPrivate.read(
                    ReadRequest.forPrivate(user, item.getResource().asPrivate()));
            String checksumOfDecryptedTestFile = checksum(decryptedFileStream);
            decryptedFileStream.close();
            return checksumOfDecryptedTestFile;
        } catch (IOException e) {
            fail(e);
        }

        return "";
    }

    @SneakyThrows
    private String checksum(InputStream input) {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] block = new byte[4096];
        int length;
        while ((length = input.read(block)) > 0) {
            digest.update(block, 0, length);
        }
        return Hex.toHexString(digest.digest());
    }

    private static void generateTestFile(String testFile, int testFileSizeInBytes) {
        try (RandomAccessFile originTestFile = new RandomAccessFile(testFile, "rw")) {
            MappedByteBuffer out = originTestFile.getChannel()
                    .map(FileChannel.MapMode.READ_WRITE, 0, testFileSizeInBytes);

            for (int i = 0; i < testFileSizeInBytes; i++) {
                out.put((byte) 'x');
            }
        } catch (IOException e) {
            log.error(e.getMessage());
        }

    }

    @ValueSource
    protected static Stream<Arguments> differentThreadsTestOptions() {
        Stream<StorageDescriptor> storageDescriptorMap = allStorages();
        List<Arguments> arguments = new ArrayList<>();

        storageDescriptorMap.forEach(storageDescriptor -> {
            //30kb - 4 threads pool size
            arguments.add(Arguments.of(storageDescriptor, 1024 * 30, 4));
            //30kb - 8 threads pool size
            arguments.add(Arguments.of(storageDescriptor, 1024 * 30, 8));
            //60kb - 4 threads pool size
            arguments.add(Arguments.of(storageDescriptor, 1024 * 60, 4));
            //60kb - 8 threads pool size
            arguments.add(Arguments.of(storageDescriptor, 1024 * 60, 8));
            //5Mb - 4 threads pool size
            arguments.add(Arguments.of(storageDescriptor, 1024 * 1024 * 5, 4));
            //5Mb - 8 threads pool size
            arguments.add(Arguments.of(storageDescriptor, 1024 * 1024 * 5, 8));
            //10Mb - 4 threads pool size - multipart upload
            arguments.add(Arguments.of(storageDescriptor, 1024 * 1024 * 10, 4));
            //10Mb - 8 threads pool size - multipart upload
            arguments.add(Arguments.of(storageDescriptor, 1024 * 1024 * 10, 8));
        });
        return arguments.stream();
    }

    private void init(WithStorageProvider.StorageDescriptor descriptor) {
        DefaultDatasafeServices datasafeServices = DatasafeServicesProvider
                .defaultDatasafeServices(descriptor.getStorageService().get(), descriptor.getLocation());
        initialize(DatasafeServicesProvider.dfsConfig(descriptor.getLocation()), datasafeServices);

        this.location = descriptor.getLocation();
        this.storage = descriptor.getStorageService().get();
    }

    protected void writeDataToFileForUser(UserIDAuth john, String filePathForWriting, String filePathForReading,
                                          CountDownLatch latch) {
        try {
            OutputStream write = writeToPrivate.write(WriteRequest.forDefaultPrivate(john, filePathForWriting));

            FileInputStream fis = new FileInputStream(filePathForReading);
            ByteStreams.copy(fis, write);

            closeQuietly(fis);
            closeQuietly(write);
        } catch (IOException e) {
            log.error(e.getMessage(), e);
        }

        latch.countDown();
    }

    @BeforeAll
    public static void setUp() {
        if(System.getenv("NUMBER_OF_TEST_USERS") != null) {
            NUMBER_OF_TEST_USERS = Integer.parseInt(System.getenv("NUMBER_OF_TEST_USERS"));
        }
        if(System.getenv("NUMBER_OF_TEST_FILES") != null) {
            NUMBER_OF_TEST_FILES = Integer.parseInt(System.getenv("NUMBER_OF_TEST_FILES"));
            EXPECTED_NUMBER_OF_FILES_PER_USER = NUMBER_OF_TEST_FILES;
        }
    }

}
