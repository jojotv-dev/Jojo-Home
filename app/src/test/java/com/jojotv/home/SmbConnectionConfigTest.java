package com.jojotv.home;

import org.junit.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.Provider;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class SmbConnectionConfigTest {
    private static void withFreshSmbProvider(Callable<Void> check) throws Exception {
        // Isolate jCIFS' process-wide state so cold-start tests do not depend on order.
        Field provider = jcifs.util.Crypto.class.getDeclaredField("provider");
        provider.setAccessible(true);
        Object previous = provider.get(null);
        provider.set(null, null);
        try {
            check.call();
        } finally {
            provider.set(null, previous);
        }
    }

    @Test
    public void smbCryptoCanBeRequestedRepeatedly() throws Exception {
        withFreshSmbProvider(() -> {
            Provider provider = SmbConnectionConfig.ensureCryptoProvider();
            assertSame(provider, SmbConnectionConfig.ensureCryptoProvider());
            assertSame(provider, jcifs.util.Crypto.getProvider());
            return null;
        });
    }

    @Test
    public void smbCryptoReusesALazilyInitializedJcifsProvider() throws Exception {
        withFreshSmbProvider(() -> {
            Provider provider = jcifs.util.Crypto.getProvider();
            assertSame(provider, SmbConnectionConfig.ensureCryptoProvider());
            assertSame(provider, SmbConnectionConfig.ensureCryptoProvider());
            return null;
        });
    }

    @Test
    public void smbCryptoReusesAnExplicitlyInitializedJcifsProvider() throws Exception {
        withFreshSmbProvider(() -> {
            Provider provider = new org.bouncycastle.jce.provider.BouncyCastleProvider();
            jcifs.util.Crypto.initProvider(provider);
            assertSame(provider, SmbConnectionConfig.ensureCryptoProvider());
            assertSame(provider, SmbConnectionConfig.ensureCryptoProvider());
            return null;
        });
    }

    @Test
    public void smbCryptoConcurrentFirstUseSharesOneProvider() throws Exception {
        withFreshSmbProvider(() -> {
            ExecutorService workers = Executors.newFixedThreadPool(8);
            CountDownLatch ready = new CountDownLatch(8);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Provider>> requests = new ArrayList<>();
            try {
                for (int i = 0; i < 8; i++) {
                    requests.add(workers.submit(() -> {
                        ready.countDown();
                        assertTrue(start.await(5, TimeUnit.SECONDS));
                        return SmbConnectionConfig.ensureCryptoProvider();
                    }));
                }
                assertTrue(ready.await(5, TimeUnit.SECONDS));
                start.countDown();
                Provider provider = requests.get(0).get(10, TimeUnit.SECONDS);
                for (Future<Provider> request : requests) assertSame(provider, request.get(10, TimeUnit.SECONDS));
                assertSame(provider, jcifs.util.Crypto.getProvider());
            } finally {
                start.countDown();
                workers.shutdownNow();
                assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
            }
            return null;
        });
    }

    @Test
    public void smbCryptoRetainsMd4ForNtlmAuthentication() throws Exception {
        withFreshSmbProvider(() -> {
            Provider provider = SmbConnectionConfig.ensureCryptoProvider();
            byte[] expected = {(byte) 0x88, 0x46, (byte) 0xf7, (byte) 0xea, (byte) 0xee, (byte) 0x8f,
                    (byte) 0xb1, 0x17, (byte) 0xad, 0x06, (byte) 0xbd, (byte) 0xd8, 0x30, (byte) 0xb7, 0x58, 0x6c};
            assertArrayEquals(expected, MessageDigest.getInstance("MD4", provider)
                    .digest("password".getBytes(StandardCharsets.UTF_16LE)));
            return null;
        });
    }

    @Test
    public void microsoftAccountKeepsTheFullEmailAndExplicitDomain() {
        jcifs.smb.NtlmPasswordAuthenticator auth = SmbConnectionConfig.authenticatorFor(
                "MicrosoftAccount\\viewer@example.com", " test:@\\% password ");

        assertEquals("MicrosoftAccount", auth.getUserDomain());
        assertEquals("viewer@example.com", auth.getUsername());
        assertEquals(" test:@\\% password ", auth.getPassword());
        assertEquals(auth.getUserDomain(), auth.clone().getUserDomain());
        assertEquals(auth.getUsername(), auth.clone().getUsername());
    }

    @Test
    public void explicitWindowsDomainTakesPrecedenceOverEmailParsing() {
        jcifs.smb.NtlmPasswordAuthenticator auth = SmbConnectionConfig.authenticatorFor(
                "WORKGROUP\\viewer@example.com", "test-password");

        assertEquals("WORKGROUP", auth.getUserDomain());
        assertEquals("viewer@example.com", auth.getUsername());
    }

    @Test
    public void localWindowsAccountAndNasLoginRetainTheirCredentials() {
        jcifs.smb.NtlmPasswordAuthenticator windows = SmbConnectionConfig.authenticatorFor("LAPTOP\\viewer", "test-password");
        jcifs.smb.NtlmPasswordAuthenticator nas = SmbConnectionConfig.authenticatorFor("viewer", "test-password");

        assertEquals("LAPTOP", windows.getUserDomain());
        assertEquals("viewer", windows.getUsername());
        assertEquals("", nas.getUserDomain());
        assertEquals("viewer", nas.getUsername());
    }

    @Test
    public void unqualifiedUpnRetainsTheExistingDomainParsing() {
        jcifs.smb.NtlmPasswordAuthenticator auth = SmbConnectionConfig.authenticatorFor("viewer@example.com", "test-password");

        assertEquals("example.com", auth.getUserDomain());
        assertEquals("viewer", auth.getUsername());
    }

    @Test
    public void microsoftAccountCredentialsSurvivePreferenceRoundTrip() {
        SmbConnectionConfig original = new SmbConnectionConfig("Laptop", "nas/c", "MicrosoftAccount\\viewer@example.com", "test-password");
        SmbConnectionConfig restored = SmbConnectionConfig.fromPrefsString(
                SmbConnectionConfig.toPrefsString(Arrays.asList(original)), "", "", "").get(0);
        jcifs.smb.NtlmPasswordAuthenticator auth = SmbConnectionConfig.authenticatorFor(restored.user, restored.password);

        assertEquals(original.user, restored.user);
        assertEquals("MicrosoftAccount", auth.getUserDomain());
        assertEquals("viewer@example.com", auth.getUsername());
        assertEquals(original.password, auth.getPassword());
    }

    @Test
    public void tvServerInputAcceptsOptionalSmbSlashes() {
        for (String server : Arrays.asList("192.168.1.254", "smb:192.168.1.254", "smb:/192.168.1.254",
                "smb://192.168.1.254", "SMB://192.168.1.254/", "\\\\192.168.1.254", "//smb://192.168.1.254")) {
            assertEquals(server, "smb://192.168.1.254/", SmbConnectionConfig.browserUrlFor(server));
        }
        assertEquals("smb://nas/Disque dur/Vidéos/", SmbConnectionConfig.browserUrlFor("smb:////nas//Disque dur/Vidéos"));
        assertEquals("", SmbConnectionConfig.browserUrlFor("  "));
    }

    @Test
    public void updateSearchReturnsToShareRootFromNestedFolders() {
        assertEquals("smb://192.168.1.254/Freebox/",
                new SmbConnectionConfig("Freebox", "192.168.1.254/Freebox/Téléchargements/", "", "").shareRootUrl());
        assertEquals("smb://nas/Disque%20dur/",
                new SmbConnectionConfig("NAS", "smb://nas/Disque%20dur/JojoHome/exports/", "", "").shareRootUrl());
    }

    @Test
    public void updateSearchPreservesShareRootsAndWindowsPaths() {
        assertEquals("smb://nas/Freebox/",
                new SmbConnectionConfig("NAS", "nas/Freebox", "", "").shareRootUrl());
        assertEquals("smb://nas/Disque dur/",
                new SmbConnectionConfig("NAS", "\\\\nas\\Disque dur\\Vidéos", "", "").shareRootUrl());
    }

    @Test
    public void serverOnlyUpdateConnectionCanResolveTheFreeboxShare() {
        assertEquals("smb://192.168.1.254/",
                new SmbConnectionConfig("Freebox", "192.168.1.254", "", "").shareRootUrl());
    }

    @Test
    public void freeboxDeletionDecodesEachPathSegmentWithoutChangingPlusSigns() {
        assertEquals(Arrays.asList("Disque dur", "Vidéos", "Emission + 100%.mkv"),
                SmbConnectionConfig.freeboxDeletePathSegments(
                        "smb://192.168.1.254/Disque%20dur/Vid%C3%A9os/Emission%20+%20100%25.mkv"));
        assertEquals(Arrays.asList("Disque dur", "Vidéos", "Emission.mkv"),
                SmbConnectionConfig.freeboxDeletePathSegments(
                        "smb://192.168.1.254/Disque dur/Vidéos/Emission.mkv"));
    }

    @Test
    public void freeboxDeletionRejectsShareRootsAndAmbiguousPaths() {
        for (String path : Arrays.asList("smb://nas/Partage/", "http://nas/Partage/video.mkv",
                "smb://nas/Partage/../video.mkv", "smb://nas/Partage/a%2Fb.mkv",
                "smb://nas/Partage/a%5Cb.mkv", "smb://nas/Partage/video.mkv?other")) {
            assertTrue(path, SmbConnectionConfig.freeboxDeletePathSegments(path).isEmpty());
        }
    }

    @Test
    public void legacySettingsBecomeOneDefaultConnection() {
        List<SmbConnectionConfig> connections = SmbConnectionConfig.fromPrefsString("", "192.168.1.10/Partage", "user", "secret");

        assertEquals(1, connections.size());
        assertEquals("NAS", connections.get(0).label);
        assertEquals("192.168.1.10/Partage", connections.get(0).server);
        assertEquals("user", connections.get(0).user);
        assertEquals("secret", connections.get(0).password);
    }

    @Test
    public void storedConnectionsRoundTrip() {
        SmbConnectionConfig first = new SmbConnectionConfig("NAS", "192.168.1.10/Partage", "user", "secret");
        SmbConnectionConfig second = new SmbConnectionConfig("Films", "192.168.1.20/Films", "admin", "pwd");
        String serialized = SmbConnectionConfig.toPrefsString(Arrays.asList(first, second));

        List<SmbConnectionConfig> restored = SmbConnectionConfig.fromPrefsString(serialized, "", "", "");

        assertEquals(2, restored.size());
        assertEquals("NAS", restored.get(0).label);
        assertEquals("Films", restored.get(1).label);
        assertTrue(restored.get(1).browserUrl().contains("smb://"));
    }

    @Test
    public void migratingSharedReadersAddsBothPcsToATvThatAlreadyHasANas() {
        SmbConnectionConfig nas = new SmbConnectionConfig("NAS", "nas/Media", "viewer", "test-nas");
        SmbConnectionConfig pc = new SmbConnectionConfig("PC", "desktop/Films", "viewer", "test-pc");
        SmbConnectionConfig laptop = new SmbConnectionConfig("Portable", "laptop/Videos", "viewer", "test-laptop");
        Map<String, SmbConnectionConfig.SyncEntry> local = SmbConnectionConfig.seedSyncEntries(Collections.emptyMap(), Arrays.asList(nas));
        Map<String, SmbConnectionConfig.SyncEntry> remote = SmbConnectionConfig.seedSyncEntries(Collections.emptyMap(), Arrays.asList(nas, pc, laptop));

        List<SmbConnectionConfig> restored = SmbConnectionConfig.connectionsFromSync(
                SmbConnectionConfig.mergeSyncEntries(local, remote), Arrays.asList(nas));

        assertEquals(3, restored.size());
        assertEquals(nas.server, restored.get(0).server);
        assertTrue(restored.stream().anyMatch(item -> item.server.equals(pc.server) && item.password.equals(pc.password)));
        assertTrue(restored.stream().anyMatch(item -> item.server.equals(laptop.server) && item.password.equals(laptop.password)));
    }

    @Test
    public void independentAdditionsFromTwoTvsSurviveInBothMergeDirections() {
        SmbConnectionConfig nas = new SmbConnectionConfig("NAS", "nas/Media", "", "");
        SmbConnectionConfig pc = new SmbConnectionConfig("PC", "desktop/Films", "", "");
        SmbConnectionConfig laptop = new SmbConnectionConfig("Portable", "laptop/Videos", "", "");
        Map<String, SmbConnectionConfig.SyncEntry> a = SmbConnectionConfig.recordSyncChanges(
                Collections.emptyMap(), Arrays.asList(nas), Arrays.asList(nas, pc), 100L, "tv163");
        Map<String, SmbConnectionConfig.SyncEntry> b = SmbConnectionConfig.recordSyncChanges(
                Collections.emptyMap(), Arrays.asList(nas), Arrays.asList(nas, laptop), 101L, "tv150");

        Map<String, SmbConnectionConfig.SyncEntry> merged = SmbConnectionConfig.mergeSyncEntries(a, b);
        assertEquals(3, SmbConnectionConfig.connectionsFromSync(merged, Arrays.asList(nas)).size());
        assertTrue(SmbConnectionConfig.sameSyncEntries(merged, SmbConnectionConfig.mergeSyncEntries(b, a)));
    }

    @Test
    public void staleTvCannotRestoreARemovedReader() {
        SmbConnectionConfig pc = new SmbConnectionConfig("PC", "desktop/Films", "viewer", "test-password");
        Map<String, SmbConnectionConfig.SyncEntry> stale = SmbConnectionConfig.seedSyncEntries(Collections.emptyMap(), Arrays.asList(pc));
        Map<String, SmbConnectionConfig.SyncEntry> deleted = SmbConnectionConfig.recordSyncChanges(
                stale, Arrays.asList(pc), Collections.emptyList(), 100L, "tv163");
        Map<String, SmbConnectionConfig.SyncEntry> merged = SmbConnectionConfig.mergeSyncEntries(stale, deleted);

        assertTrue(SmbConnectionConfig.connectionsFromSync(merged, Arrays.asList(pc)).isEmpty());
        assertTrue(merged.get(SmbConnectionConfig.syncKey(pc)).deleted);
        assertEquals(null, merged.get(SmbConnectionConfig.syncKey(pc)).connection);
    }

    @Test
    public void removingTheLastReaderRetainsASynchronizedDeletion() {
        SmbConnectionConfig nas = new SmbConnectionConfig("NAS", "nas/Media", "", "");
        Map<String, SmbConnectionConfig.SyncEntry> deleted = SmbConnectionConfig.recordSyncChanges(
                Collections.emptyMap(), Arrays.asList(nas), Collections.emptyList(), 100L, "tv163");
        Map<String, SmbConnectionConfig.SyncEntry> stale = SmbConnectionConfig.seedSyncEntries(Collections.emptyMap(), Arrays.asList(nas));

        assertEquals(1, deleted.size());
        assertTrue(SmbConnectionConfig.connectionsFromSync(SmbConnectionConfig.mergeSyncEntries(deleted, stale), Collections.emptyList()).isEmpty());
    }

    @Test
    public void newerLabelAndCredentialsReplaceTheSameSharedReader() {
        SmbConnectionConfig before = new SmbConnectionConfig("PC", "desktop/Films", "viewer", "test-old");
        SmbConnectionConfig after = new SmbConnectionConfig("PC fixe", "desktop/Films", "viewer", "test-new");
        Map<String, SmbConnectionConfig.SyncEntry> edited = SmbConnectionConfig.recordSyncChanges(
                Collections.emptyMap(), Arrays.asList(before), Arrays.asList(after), 100L, "tv163");
        Map<String, SmbConnectionConfig.SyncEntry> stale = SmbConnectionConfig.seedSyncEntries(Collections.emptyMap(), Arrays.asList(before));
        List<SmbConnectionConfig> restored = SmbConnectionConfig.connectionsFromSync(
                SmbConnectionConfig.mergeSyncEntries(stale, edited), Arrays.asList(before));

        assertEquals(1, restored.size());
        assertEquals("PC fixe", restored.get(0).label);
        assertEquals("test-new", restored.get(0).password);
    }

    @Test
    public void readdingAReaderDefeatsItsDeletionEvenAfterTheClockMovesBackwards() {
        SmbConnectionConfig pc = new SmbConnectionConfig("PC", "desktop/Films", "", "");
        Map<String, SmbConnectionConfig.SyncEntry> deleted = SmbConnectionConfig.recordSyncChanges(
                Collections.emptyMap(), Arrays.asList(pc), Collections.emptyList(), 100L, "tv163");
        Map<String, SmbConnectionConfig.SyncEntry> added = SmbConnectionConfig.recordSyncChanges(
                deleted, Collections.emptyList(), Arrays.asList(pc), 50L, "tv150");

        assertEquals(101L, added.get(SmbConnectionConfig.syncKey(pc)).changedAt);
        assertEquals(1, SmbConnectionConfig.connectionsFromSync(SmbConnectionConfig.mergeSyncEntries(added, deleted), Collections.emptyList()).size());
    }

    @Test
    public void editingTheAddressRemovesTheOldIdentityAndPublishesTheNewOne() {
        SmbConnectionConfig before = new SmbConnectionConfig("PC", "desktop/Films", "", "");
        SmbConnectionConfig after = new SmbConnectionConfig("PC", "desktop/Videos", "", "");
        Map<String, SmbConnectionConfig.SyncEntry> edited = SmbConnectionConfig.recordSyncChanges(
                Collections.emptyMap(), Arrays.asList(before), Arrays.asList(after), 100L, "tv163");

        assertTrue(edited.get(SmbConnectionConfig.syncKey(before)).deleted);
        assertEquals(after.server, SmbConnectionConfig.connectionsFromSync(edited, Arrays.asList(before)).get(0).server);
    }

    @Test
    public void restoringNewReadersKeepsTheReceivingTvsDefaultAndOrder() {
        SmbConnectionConfig nas = new SmbConnectionConfig("NAS", "nas/Media", "", "");
        SmbConnectionConfig pc = new SmbConnectionConfig("PC", "desktop/Films", "", "");
        SmbConnectionConfig laptop = new SmbConnectionConfig("Portable", "laptop/Videos", "", "");
        Map<String, SmbConnectionConfig.SyncEntry> entries = SmbConnectionConfig.seedSyncEntries(Collections.emptyMap(), Arrays.asList(nas, pc, laptop));
        List<SmbConnectionConfig> restored = SmbConnectionConfig.connectionsFromSync(entries, Arrays.asList(pc, nas));

        assertEquals(Arrays.asList(pc, nas, laptop), restored);
    }

    @Test
    public void sameTimeConflictsConvergeAndDeletionWinsAtThatTime() {
        SmbConnectionConfig first = new SmbConnectionConfig("A", "desktop/Films", "", "");
        SmbConnectionConfig second = new SmbConnectionConfig("B", "desktop/Films", "", "");
        String key = SmbConnectionConfig.syncKey(first);
        Map<String, SmbConnectionConfig.SyncEntry> a = Collections.singletonMap(key, new SmbConnectionConfig.SyncEntry(first, 100L, "a", false));
        Map<String, SmbConnectionConfig.SyncEntry> b = Collections.singletonMap(key, new SmbConnectionConfig.SyncEntry(second, 100L, "b", false));
        Map<String, SmbConnectionConfig.SyncEntry> deletion = Collections.singletonMap(key, new SmbConnectionConfig.SyncEntry(null, 100L, "a", true));

        assertTrue(SmbConnectionConfig.sameSyncEntries(SmbConnectionConfig.mergeSyncEntries(a, b), SmbConnectionConfig.mergeSyncEntries(b, a)));
        assertTrue(SmbConnectionConfig.mergeSyncEntries(b, deletion).get(key).deleted);
    }

    @Test
    public void unchangedReadersKeepTheirExistingEditClock() {
        SmbConnectionConfig pc = new SmbConnectionConfig("PC", "desktop/Films", "", "");
        Map<String, SmbConnectionConfig.SyncEntry> saved = SmbConnectionConfig.recordSyncChanges(
                Collections.emptyMap(), Collections.emptyList(), Arrays.asList(pc), 100L, "tv163");

        assertTrue(SmbConnectionConfig.sameSyncEntries(saved, SmbConnectionConfig.recordSyncChanges(
                saved, Arrays.asList(pc), Arrays.asList(pc), 200L, "tv150")));
    }

    @Test
    public void syncIdentityNormalizesSmbAddressAndLoginCasing() {
        assertEquals(SmbConnectionConfig.syncKey(new SmbConnectionConfig("PC", "\\\\DESKTOP\\Films", "VIEWER", "")),
                SmbConnectionConfig.syncKey(new SmbConnectionConfig("Films", "smb://desktop/Films/", "viewer", "")));
    }
}
