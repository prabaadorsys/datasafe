package de.adorsys.datasafe.encrypiton.impl.cmsencryption;

import de.adorsys.datasafe.encrypiton.api.cmsencryption.CMSEncryptionService;
import de.adorsys.datasafe.encrypiton.api.keystore.KeyStoreService;
import de.adorsys.datasafe.encrypiton.api.types.keystore.*;
import de.adorsys.datasafe.encrypiton.impl.keystore.KeyStoreServiceImpl;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.cms.CMSException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.KeyStore;

import static de.adorsys.datasafe.encrypiton.api.types.keystore.KeyStoreCreationConfig.PATH_KEY_ID;
import static de.adorsys.datasafe.encrypiton.api.types.keystore.KeyStoreCreationConfig.SYMM_KEY_ID;
import static de.adorsys.datasafe.encrypiton.impl.cmsencryption.KeyStoreUtil.getKeys;
import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
class SymetricEncryptionTest {

    private static final String MESSAGE_CONTENT = "message content";

    private CMSEncryptionService cmsEncryptionService = new CMSEncryptionServiceImpl(new DefaultCMSEncryptionConfig());
    private KeyStoreService keyStoreService = new KeyStoreServiceImpl();
    private ReadKeyPassword readKeyPassword = new ReadKeyPassword("readkeypassword");
    private ReadStorePassword readStorePassword = new ReadStorePassword("readstorepassword");
    private KeyStoreAuth keyStoreAuth = new KeyStoreAuth(readStorePassword, readKeyPassword);
    private KeyStoreCreationConfig config = new KeyStoreCreationConfig(1, 1);
    private KeyStore keyStore = keyStoreService.createKeyStore(keyStoreAuth, KeyStoreType.DEFAULT, config);
    private KeyStoreAccess keyStoreAccess = new KeyStoreAccess(keyStore, keyStoreAuth);

    @Test
    @SneakyThrows
    void symetricStreamEncryptAndDecryptTest() {
        SecretKey secretKey = keyStoreService.getSecretKey(keyStoreAccess, SYMM_KEY_ID);
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        OutputStream encryptionStream = cmsEncryptionService.buildEncryptionOutputStream(outputStream,
                secretKey, SYMM_KEY_ID);

        encryptionStream.write(MESSAGE_CONTENT.getBytes());
        encryptionStream.close();
        byte[] byteArray = outputStream.toByteArray();

        ByteArrayInputStream inputStream = new ByteArrayInputStream(byteArray);
        InputStream decryptionStream = cmsEncryptionService.buildDecryptionInputStream(
                inputStream, keyIds -> getKeys(keyIds, keyStoreAccess));

        assertThat(decryptionStream).hasContent(MESSAGE_CONTENT);
        log.debug("en and decrypted successfully");
    }

    @Test()
    @SneakyThrows
    void symetricNegativeStreamEncryptAndDecryptTest() {
        // This is the keystore we use to encrypt, it has SYMM_KEY_ID and PATH_KEY_ID symm. keys.
        keyStoreService.createKeyStore(keyStoreAuth, KeyStoreType.DEFAULT, config);
        SecretKey realSecretKey = keyStoreService.getSecretKey(keyStoreAccess, SYMM_KEY_ID);
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        // Test consist in encrypting with real secret key, but use fake secretKeyId - PATH_KEY_ID
        OutputStream encryptionStream = cmsEncryptionService.buildEncryptionOutputStream(outputStream,
                realSecretKey, PATH_KEY_ID);

        encryptionStream.write(MESSAGE_CONTENT.getBytes());
        encryptionStream.close();
        byte[] byteArray = outputStream.toByteArray();

        ByteArrayInputStream inputStream = new ByteArrayInputStream(byteArray);
        // Opening envelope with wrong key must throw a cms exception.
        Assertions.assertThrows(CMSException.class, () ->
            cmsEncryptionService.buildDecryptionInputStream(inputStream, keyIds -> getKeys(keyIds, keyStoreAccess))
        );
    }
}
