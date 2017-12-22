/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.locksettings.recoverablekeystore;

import static android.security.recoverablekeystore.KeyStoreRecoveryMetadata.TYPE_PASSWORD;
import static android.security.recoverablekeystore.KeyStoreRecoveryMetadata.TYPE_PATTERN;
import static android.security.recoverablekeystore.KeyStoreRecoveryMetadata.TYPE_PIN;

import static com.android.internal.widget.LockPatternUtils.CREDENTIAL_TYPE_PASSWORD;
import static com.android.internal.widget.LockPatternUtils.CREDENTIAL_TYPE_PATTERN;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.security.keystore.AndroidKeyStoreSecretKey;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import com.android.server.locksettings.recoverablekeystore.storage.RecoverableKeyStoreDb;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.Arrays;
import java.util.Map;
import java.util.Random;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class KeySyncTaskTest {
    private static final String KEY_ALGORITHM = "AES";
    private static final String ANDROID_KEY_STORE_PROVIDER = "AndroidKeyStore";
    private static final String WRAPPING_KEY_ALIAS = "KeySyncTaskTest/WrappingKey";
    private static final String DATABASE_FILE_NAME = "recoverablekeystore.db";
    private static final int TEST_USER_ID = 1000;
    private static final int TEST_APP_UID = 10009;
    private static final String TEST_APP_KEY_ALIAS = "rcleaver";
    private static final int TEST_GENERATION_ID = 2;
    private static final int TEST_CREDENTIAL_TYPE = CREDENTIAL_TYPE_PASSWORD;
    private static final String TEST_CREDENTIAL = "password1234";
    private static final byte[] THM_ENCRYPTED_RECOVERY_KEY_HEADER =
            "V1 THM_encrypted_recovery_key".getBytes(StandardCharsets.UTF_8);

    @Mock private KeySyncTask.RecoverableSnapshotConsumer mRecoverableSnapshotConsumer;
    @Mock private PlatformKeyManager mPlatformKeyManager;

    @Captor private ArgumentCaptor<byte[]> mSaltCaptor;
    @Captor private ArgumentCaptor<byte[]> mEncryptedRecoveryKeyCaptor;
    @Captor private ArgumentCaptor<Map<String, byte[]>> mEncryptedApplicationKeysCaptor;

    private RecoverableKeyStoreDb mRecoverableKeyStoreDb;
    private File mDatabaseFile;
    private KeyPair mKeyPair;
    private AndroidKeyStoreSecretKey mWrappingKey;
    private PlatformEncryptionKey mEncryptKey;

    private KeySyncTask mKeySyncTask;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        Context context = InstrumentationRegistry.getTargetContext();
        mDatabaseFile = context.getDatabasePath(DATABASE_FILE_NAME);
        mRecoverableKeyStoreDb = RecoverableKeyStoreDb.newInstance(context);
        mKeyPair = SecureBox.genKeyPair();

        mKeySyncTask = new KeySyncTask(
                mRecoverableKeyStoreDb,
                TEST_USER_ID,
                TEST_CREDENTIAL_TYPE,
                TEST_CREDENTIAL,
                () -> mPlatformKeyManager,
                mRecoverableSnapshotConsumer,
                () -> mKeyPair.getPublic());

        mWrappingKey = generateAndroidKeyStoreKey();
        mEncryptKey = new PlatformEncryptionKey(TEST_GENERATION_ID, mWrappingKey);
        when(mPlatformKeyManager.getDecryptKey()).thenReturn(
                new PlatformDecryptionKey(TEST_GENERATION_ID, mWrappingKey));
    }

    @After
    public void tearDown() {
        mRecoverableKeyStoreDb.close();
        mDatabaseFile.delete();
    }

    @Test
    public void isPin_isTrueForNumericString() {
        assertTrue(KeySyncTask.isPin("3298432574398654376547"));
    }

    @Test
    public void isPin_isFalseForStringContainingLetters() {
        assertFalse(KeySyncTask.isPin("398i54369548654"));
    }

    @Test
    public void isPin_isFalseForStringContainingSymbols() {
        assertFalse(KeySyncTask.isPin("-3987543643"));
    }

    @Test
    public void hashCredentials_returnsSameHashForSameCredentialsAndSalt() {
        String credentials = "password1234";
        byte[] salt = randomBytes(16);

        assertArrayEquals(
                KeySyncTask.hashCredentials(salt, credentials),
                KeySyncTask.hashCredentials(salt, credentials));
    }

    @Test
    public void hashCredentials_returnsDifferentHashForDifferentCredentials() {
        byte[] salt = randomBytes(16);

        assertFalse(
                Arrays.equals(
                    KeySyncTask.hashCredentials(salt, "password1234"),
                    KeySyncTask.hashCredentials(salt, "password12345")));
    }

    @Test
    public void hashCredentials_returnsDifferentHashForDifferentSalt() {
        String credentials = "wowmuch";

        assertFalse(
                Arrays.equals(
                        KeySyncTask.hashCredentials(randomBytes(64), credentials),
                        KeySyncTask.hashCredentials(randomBytes(64), credentials)));
    }

    @Test
    public void hashCredentials_returnsDifferentHashEvenIfConcatIsSame() {
        assertFalse(
                Arrays.equals(
                        KeySyncTask.hashCredentials(utf8Bytes("123"), "4567"),
                        KeySyncTask.hashCredentials(utf8Bytes("1234"), "567")));
    }

    @Test
    public void getUiFormat_returnsPinIfPin() {
        assertEquals(TYPE_PIN,
                KeySyncTask.getUiFormat(CREDENTIAL_TYPE_PASSWORD, "1234"));
    }

    @Test
    public void getUiFormat_returnsPasswordIfPassword() {
        assertEquals(TYPE_PASSWORD,
                KeySyncTask.getUiFormat(CREDENTIAL_TYPE_PASSWORD, "1234a"));
    }

    @Test
    public void getUiFormat_returnsPatternIfPattern() {
        assertEquals(TYPE_PATTERN,
                KeySyncTask.getUiFormat(CREDENTIAL_TYPE_PATTERN, "1234"));

    }

    @Test
    public void run_doesNotSendAnythingIfNoKeysToSync() throws Exception {
        // TODO: proper test here, once we have proper implementation for checking that keys need
        // to be synced.
        mKeySyncTask.run();

        verifyZeroInteractions(mRecoverableSnapshotConsumer);
    }

    @Test
    public void run_sendsEncryptedKeysIfAvailableToSync() throws Exception {
        SecretKey applicationKey = generateKey();
        mRecoverableKeyStoreDb.setPlatformKeyGenerationId(TEST_USER_ID, TEST_GENERATION_ID);
        mRecoverableKeyStoreDb.insertKey(
                TEST_USER_ID,
                TEST_APP_UID,
                TEST_APP_KEY_ALIAS,
                WrappedKey.fromSecretKey(mEncryptKey, applicationKey));

        mKeySyncTask.run();

        verify(mRecoverableSnapshotConsumer).accept(
                mSaltCaptor.capture(),
                mEncryptedRecoveryKeyCaptor.capture(),
                mEncryptedApplicationKeysCaptor.capture());
        byte[] lockScreenHash = KeySyncTask.hashCredentials(
                mSaltCaptor.getValue(), TEST_CREDENTIAL);
        // TODO: what should vault params be here?
        byte[] recoveryKey = decryptThmEncryptedKey(
                lockScreenHash,
                mEncryptedRecoveryKeyCaptor.getValue(),
                /*vaultParams=*/ new byte[0]);
        Map<String, byte[]> applicationKeys = mEncryptedApplicationKeysCaptor.getValue();
        assertEquals(1, applicationKeys.size());
        byte[] appKey = KeySyncUtils.decryptApplicationKey(
                recoveryKey, applicationKeys.get(TEST_APP_KEY_ALIAS));
        assertArrayEquals(applicationKey.getEncoded(), appKey);
    }

    private byte[] decryptThmEncryptedKey(
            byte[] lockScreenHash, byte[] encryptedKey, byte[] vaultParams) throws Exception {
        byte[] locallyEncryptedKey = SecureBox.decrypt(
                mKeyPair.getPrivate(),
                /*sharedSecret=*/ KeySyncUtils.calculateThmKfHash(lockScreenHash),
                /*header=*/ KeySyncUtils.concat(THM_ENCRYPTED_RECOVERY_KEY_HEADER, vaultParams),
                encryptedKey
        );
        return KeySyncUtils.decryptRecoveryKey(lockScreenHash, locallyEncryptedKey);
    }

    private SecretKey generateKey() throws Exception {
        KeyGenerator keyGenerator = KeyGenerator.getInstance(KEY_ALGORITHM);
        keyGenerator.init(/*keySize=*/ 256);
        return keyGenerator.generateKey();
    }

    private AndroidKeyStoreSecretKey generateAndroidKeyStoreKey() throws Exception {
        KeyGenerator keyGenerator = KeyGenerator.getInstance(
                KEY_ALGORITHM,
                ANDROID_KEY_STORE_PROVIDER);
        keyGenerator.init(new KeyGenParameterSpec.Builder(
                WRAPPING_KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return (AndroidKeyStoreSecretKey) keyGenerator.generateKey();
    }

    private static byte[] utf8Bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] randomBytes(int n) {
        byte[] bytes = new byte[n];
        new Random().nextBytes(bytes);
        return bytes;
    }
}
