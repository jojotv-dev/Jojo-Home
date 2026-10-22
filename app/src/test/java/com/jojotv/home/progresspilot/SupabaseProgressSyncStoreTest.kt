package com.jojotv.home.progresspilot

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.nuvio.tv.data.local.WatchProgressPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.util.UUID

class SupabaseProgressSyncStoreTest {
    @get:Rule val temporary=TemporaryFolder()
    private val gson=GsonBuilder().serializeNulls().create()
    private val active=MutableStateFlow(1)
    private val gate=PilotRequestGate()
    private val endpoint=PilotEndpoint("https://test-project.supabase.co","sb_publishable_test")
    private val bound=PilotScope(SupabaseProgressPilotTest.OWNER,SupabaseProgressPilotTest.PROFILE,1,
        SupabaseProgressPilotTest.INSTANCE,SupabaseProgressPilotTest.DEVICE_A,
        SupabaseProgressPilotTest.DEVICE_A,SupabaseProgressPilotTest.DEVICE_B,UUID.randomUUID().toString())
    private val config=MutableStateFlow(PilotLocalSettings(deviceId=bound.deviceId,endpoint=endpoint,scope=bound,
        session=PilotAuthSession(bound.ownerId,UUID.randomUUID().toString(),"fake-access","fake-refresh",Long.MAX_VALUE),
        cloudKeyEpoch=UUID.randomUUID().toString(),cloudActivationId=UUID.randomUUID().toString()))
    private val requests=mutableListOf<PilotHttpRequest>()
    private val rows=linkedMapOf<String,CloudProgressRow>()
    private val receipts=mutableMapOf<String,CloudProgressAck>()
    private var head=0L
    private var loseWriteResponse=false
    private var onRequest: (() -> Unit)?=null
    private lateinit var jobs: Job
    private lateinit var io: CoroutineScope
    private val stores=mutableMapOf<String,DataStore<Preferences>>()
    private lateinit var cloud: SupabaseProgressSyncStore
    private lateinit var preferences: WatchProgressPreferences
    private val progressKey=stringPreferencesKey("watch_progress_map")
    private val journalKey=stringPreferencesKey("cloud_progress_journal_v1")
    private val templatesKey=stringPreferencesKey("cloud_catalog_templates_v1")
    private val indexKey=stringPreferencesKey("cloud_progress_index_v1")
    private val access=object : CloudProgressAccess {
        override val settings=config
        override fun cloudContext()=if(config.value.mode==PilotMode.SUPABASE_PROGRESS)
            PilotCaptureContext(bound,Long.MAX_VALUE,ByteArray(32){7},config.value.cloudActivationId) else null
        override fun cloudRequestPermit(capture: PilotCaptureContext)=gate.allow(endpoint,bound.ownerId,
            setOf("rest/v1/rpc/progress_sync_read_v2","rest/v1/rpc/progress_sync_write_v2"),bound,capture.activationId)
        override fun replaceSession(previous: PilotAuthSession,next: PilotAuthSession) { config.value=config.value.copy(session=next) }
        override fun recordDelivery(issue: String?,acknowledged: Boolean,retryAtMs: Long) { config.value=config.value.copy(lastIssue=issue,nextAttemptMs=retryAtMs) }
    }
    private val http=object : PilotHttpExecutor {
        override fun prepare(request: PilotHttpRequest)=object : PilotHttpCall {
            override fun cancel() {}
            override fun execute(): PilotHttpResponse {
                requests+=request
                onRequest?.invoke()
                val args=JsonParser.parseString(request.body).asJsonObject
                if(request.url.endsWith("progress_sync_write_v2")) {
                    val acks=args.getAsJsonArray("p_ops").map { item ->
                        val op=gson.fromJson(item,CloudProgressOperation::class.java)
                        receipts[op.operation_id]?.copy(status="duplicate") ?: run {
                            val rev=rows[op.payload.content_key]?.server_revision ?: 0L
                            val ack=if(rev==op.expected_revision) {
                                head++
                                rows[op.payload.content_key]=op.payload.copy(server_revision=head)
                                CloudProgressAck(op.operation_id,"applied",head)
                            } else CloudProgressAck(op.operation_id,"conflict",rev)
                            receipts[op.operation_id]=ack
                            ack
                        }
                    }
                    if(loseWriteResponse) { loseWriteResponse=false;throw IOException("synthetic lost response") }
                    return PilotHttpResponse(200,gson.toJson(acks))
                }
                val after=args.get("p_after").asLong
                val page=rows.values.filter { it.server_revision>after }.sortedBy { it.server_revision }.take(200)
                val next=if(page.size==200) page.last().server_revision else head
                val point=args.get("p_content")?.takeUnless { it.isJsonNull }?.asString?.let { rows[it] }
                return PilotHttpResponse(200,gson.toJson(CloudProgressPage(page,next,head,point)))
            }
        }
    }

    @Before fun setup() { reopen() }
    private fun local(name: String): DataStore<Preferences> = stores.getOrPut(name) {
        PreferenceDataStoreFactory.create(scope=io) { temporary.root.resolve("$name.preferences_pb") }
    }
    private fun reopen() {
        jobs=SupervisorJob();io=CoroutineScope(jobs+Dispatchers.IO)
        cloud=SupabaseProgressSyncStore(access,active,SupabaseProgressApi(http,gate),{ local("cloud") },{ bound.localProfileInstanceId },
            elapsedRealtimeMs={ 1000L })
        preferences=WatchProgressPreferences({ local("nas-$it") },active,cloudStore={ cloud })
    }
    private fun activate() { config.value=config.value.copy(mode=PilotMode.SUPABASE_PROGRESS,cloudActivationId=UUID.randomUUID().toString()) }
    @After fun close()=runBlocking { io.cancel();jobs.join() }
    private suspend fun journal()=cloud.journal(local("cloud").data.first())

    @Test fun defaultNasOnlyNeverCreatesCloudCacheOrNetworkRequests()=runBlocking {
        preferences.saveProgress(SupabaseProgressPilotTest.progress())
        assertFalse(temporary.root.resolve("cloud.preferences_pb").exists())
        assertTrue(cloud.sync().isSuccess)
        assertTrue(requests.isEmpty())
    }

    @Test fun activationDoesNotImportOldNasProgressAndReturnRestoresUntouchedNas()=runBlocking {
        val old=SupabaseProgressPilotTest.progress()
        preferences.saveProgress(old);val nas=local("nas-1").data.first()
        activate()
        assertTrue(preferences.getAllRawEntries().isEmpty())
        assertTrue(journal().pending.isEmpty())
        preferences.saveProgress(old.copy(position=1234,lastWatched=2000))
        assertEquals(nas,local("nas-1").data.first())
        config.value=config.value.copy(mode=PilotMode.NAS_ONLY);gate.stop()
        assertEquals(old,preferences.getAllRawEntries()[old.storageKey])
    }

    @Test fun localCheckpointAndTechnicalOperationCommitTogetherWithoutUsingQueueAsResume()=runBlocking {
        activate();val saved=SupabaseProgressPilotTest.progress()
        preferences.saveProgress(saved)
        val persisted=local("cloud").data.first()
        assertTrue(persisted[progressKey]!!.contains("Private title"))
        assertEquals(1,cloud.journal(persisted).pending.size)
        val stripped=persisted.toMutablePreferences().apply { remove(progressKey) }
        // A queue alone contains no title, route, or WatchProgress, and is never projected for resume.
        assertFalse(stripped.asMap().values.joinToString().contains("Private title"))
        assertEquals(saved,preferences.getAllRawEntries()[saved.storageKey])
    }

    fun peer(name: String, id: String): Pair<SupabaseProgressSyncStore, WatchProgressPreferences> {
        val binding=bound.copy(deviceId=id,deviceC=if(id==bound.deviceB) null else id)
        val peerConfig=MutableStateFlow(config.value.copy(deviceId=id,scope=binding,
            session=PilotAuthSession(binding.ownerId,UUID.randomUUID().toString(),"fake-access","fake-refresh",Long.MAX_VALUE),
            cloudActivationId=UUID.randomUUID().toString()))
        val peerGate=PilotRequestGate()
        val peerAccess=object : CloudProgressAccess {
            override val settings=peerConfig
            override fun cloudContext()=PilotCaptureContext(binding,Long.MAX_VALUE,ByteArray(32){7},peerConfig.value.cloudActivationId)
            override fun cloudRequestPermit(capture: PilotCaptureContext)=peerGate.allow(endpoint,binding.ownerId,
                setOf("rest/v1/rpc/progress_sync_read_v2","rest/v1/rpc/progress_sync_write_v2"),binding,capture.activationId)
            override fun replaceSession(previous: PilotAuthSession,next: PilotAuthSession) { peerConfig.value=peerConfig.value.copy(session=next) }
            override fun recordDelivery(issue: String?,acknowledged: Boolean,retryAtMs: Long) {
                peerConfig.value=peerConfig.value.copy(lastIssue=issue,nextAttemptMs=retryAtMs)
            }
        }
        val remote=SupabaseProgressSyncStore(peerAccess,active,SupabaseProgressApi(http,peerGate),{ local(name) },
            { binding.localProfileInstanceId },elapsedRealtimeMs={1000L})
        return remote to WatchProgressPreferences({local("$name-nas-$it")},active,cloudStore={remote})
    }
    @Test fun threeDevicesExchangePositionsAndMasksAndCatchUpAfterBeingOffline()=runBlocking {
        activate()
        val (second,secondPreferences)=peer("peer-b",bound.deviceB)
        val (third,thirdPreferences)=peer("peer-c",UUID.randomUUID().toString())
        val saved=SupabaseProgressPilotTest.progress().copy(position=120000,duration=600000)
        secondPreferences.registerCloudContent(saved.copy(position=0))
        thirdPreferences.registerCloudContent(saved.copy(position=0))
        preferences.saveProgress(saved);assertTrue(cloud.sync().isSuccess)
        assertTrue(second.sync().isSuccess)
        assertEquals(120000L,secondPreferences.getAllRawEntries()[saved.storageKey]!!.position)
        val advanced=saved.copy(position=240000,lastWatched=saved.lastWatched+1000)
        secondPreferences.saveProgress(advanced);assertTrue(second.sync().isSuccess)
        assertTrue(cloud.sync().isSuccess);assertTrue(third.sync().isSuccess)
        listOf(preferences,secondPreferences,thirdPreferences).forEach {
            assertEquals(240000L,it.getAllRawEntries()[saved.storageKey]!!.position)
        }
        third.setResumeHidden(saved.contentId,true);assertTrue(third.sync().isSuccess)
        assertTrue(cloud.sync().isSuccess);assertTrue(second.sync().isSuccess)
        listOf(preferences,secondPreferences,thirdPreferences).forEach {
            val (progress,hidden)=it.getHomeProgressSnapshot(1)
            assertEquals(240000L,progress.single().position);assertTrue(saved.storageKey in hidden)
        }
        assertEquals(3L,rows.values.single().server_revision)
        assertTrue(receipts.values.all { it.status=="applied" })
    }

    @Test fun everySenderReachesBothEmptyHomeCachesWithoutOpeningACatalogue()=runBlocking {
        activate()
        val (second,secondPreferences)=peer("empty-b",bound.deviceB)
        val (third,thirdPreferences)=peer("empty-c",UUID.randomUUID().toString())
        val devices=listOf(cloud to preferences,second to secondPreferences,third to thirdPreferences)
        var sequence=0
        for ((senderIndex,sender) in devices.withIndex()) {
            val base=SupabaseProgressPilotTest.progress().copy(addonBaseUrl=null,poster=null,backdrop=null,logo=null)
            val samples=listOf(
                base.copy(contentId="freebox:smb://nas/Videos/Film-$senderIndex.mkv",contentType="freebox"),
                base.copy(contentId="iptv:provider:movie:$senderIndex",contentType="movie",videoId="iptv:provider:movie:$senderIndex"),
                base.copy(contentId="iptv:provider:series:$senderIndex",contentType="series",season=1,episode=senderIndex+1,
                    videoId="iptv:provider:episode:$senderIndex"),
                base.copy(contentId="tizentube:video00000$senderIndex",contentType="video",videoId="video00000$senderIndex"))
            for (sample in samples) {
                val saved=sample.copy(lastWatched=10000L + ++sequence,position=120000,duration=1200000)
                devices.filterIndexed { index,_ -> index!=senderIndex }.forEach { (_,prefs) ->
                    assertNull(prefs.getAllRawEntries()[saved.storageKey])
                }
                sender.second.saveProgress(saved); assertTrue(sender.first.sync().isSuccess)
                devices.filterIndexed { index,_ -> index!=senderIndex }.forEach { (remote,prefs) ->
                    assertTrue(remote.sync().isSuccess)
                    val home=prefs.observeContinueWatching(1).first().single { it.storageKey==saved.storageKey }
                    assertEquals(saved.contentId,home.contentId);assertEquals(saved.name,home.name)
                    assertEquals(saved.position,home.position);assertEquals(saved.duration,home.duration)
                    assertEquals(saved.season,home.season);assertEquals(saved.episode,home.episode)
                }
            }
        }
        assertEquals(12,rows.size)
        assertTrue(devices.all { (_,prefs) -> prefs.observeContinueWatching(1).first().size==12 })
    }

    @Test fun invalidEncryptedPresentationCannotAdvanceTheCursorOrEraseTheKnownHomeCache()=runBlocking {
        activate()
        val saved=SupabaseProgressPilotTest.progress().copy(contentId="freebox:/Videos/Film.mkv",contentType="freebox",addonBaseUrl=null)
        preferences.saveProgress(saved);assertTrue(cloud.sync().isSuccess)
        val before=journal();val visible=preferences.getAllRawEntries()
        val old=rows.values.single()
        rows[old.content_key]=old.copy(server_revision=++head,sealed_metadata=java.util.Base64.getEncoder().encodeToString(ByteArray(40)))
        assertTrue(cloud.sync().isFailure)
        assertEquals(before.cursor,journal().cursor);assertEquals(visible,preferences.getAllRawEntries())
    }

    @Test fun identifiedTizenPlaybackWithUnknownTimingStillHasAHomeCard()=runBlocking {
        activate()
        val saved=SupabaseProgressPilotTest.progress().copy(contentId="tizentube:video000000",contentType="video",videoId="video000000",
            addonBaseUrl=null,position=0,duration=0)
        val row=requireNotNull(CloudProgressRow.from(access.cloudContext()!!,saved)).copy(server_revision=++head)
        rows[row.content_key]=row
        assertTrue(cloud.sync().isSuccess)
        assertEquals(saved.contentId,preferences.observeContinueWatching(1).first().single().contentId)
    }

    @Test fun restartRetainsCacheQueueAndIdenticalOperationForReplay()=runBlocking {
        activate();val saved=SupabaseProgressPilotTest.progress();preferences.saveProgress(saved)
        val pending=journal().pending.single()
        io.cancel();jobs.join();stores.clear();reopen()
        assertEquals(saved,preferences.getAllRawEntries()[saved.storageKey])
        assertEquals(pending,journal().pending.single())
    }

    @Test fun lostAcknowledgementReplaysSameOperationAndNeverDuplicatesTheServerRow()=runBlocking {
        activate();preferences.saveProgress(SupabaseProgressPilotTest.progress())
        val op=journal().pending.single();loseWriteResponse=true
        assertTrue(cloud.sync().isFailure)
        assertEquals(op,journal().pending.single())
        assertTrue(op.operation_id in journal().transmitted)
        assertTrue(cloud.sync(force=true).isSuccess)
        assertTrue(journal().pending.isEmpty())
        assertEquals(1,rows.size);assertEquals(1,receipts.size)
        assertEquals(1L,head)
        assertEquals(2,requests.count { it.url.endsWith("progress_sync_write_v2") })
    }

    @Test fun disablingDuringRequestDoesNotApplyAcknowledgementToAnotherSource()=runBlocking {
        activate();preferences.saveProgress(SupabaseProgressPilotTest.progress())
        val original=journal().pending.single()
        onRequest={ config.value=config.value.copy(mode=PilotMode.NAS_ONLY);gate.stop() }
        assertTrue(cloud.sync().isFailure)
        assertEquals(original,journal().pending.single())
        assertNull(local("nas-1").data.first()[progressKey])
    }

    @Test fun activationGenerationRejectsAnOldResponseEvenAfterSameSourceIsReenabled()=runBlocking {
        activate();preferences.saveProgress(SupabaseProgressPilotTest.progress())
        onRequest={ activate() }
        assertTrue(cloud.sync().isFailure)
        assertEquals(1,journal().pending.size)
    }

    @Test fun cloudRowsCanBeResolvedFromCatalogueWithoutImportingItsPosition()=runBlocking {
        activate();val template=SupabaseProgressPilotTest.progress()
        val key=access.cloudContext()!!.contentKey(template.storageKey)
        rows[key]=CloudProgressRow(key,"film",7654,10000,null,"in_progress",false,10,1);head=1
        assertTrue(cloud.sync().isSuccess)
        assertTrue(preferences.getAllRawEntries().isEmpty())
        preferences.registerCloudContent(template.copy(position=9999,lastWatched=99999))
        assertEquals(7654L,preferences.getAllRawEntries()[template.storageKey]?.position)
        assertTrue(journal().pending.isEmpty())
    }

    @Test fun receivedIptvMovieResolvesAnAlreadyDownloadedCheckpointWithoutACloudWrite()=runBlocking {
        activate()
        val movie=com.jojotv.home.iptv.IptvItem("iptv:provider:movie:17","provider","17",
            com.jojotv.home.iptv.ContentType.MOVIE,"Movie")
        val incoming=com.jojotv.home.iptv.IptvCloudItem.from(movie).item()
        val template=requireNotNull(com.jojotv.home.iptv.iptvCloudProgressTemplate(incoming,movie.id))
        val key=access.cloudContext()!!.contentKey(template.storageKey)
        rows[key]=CloudProgressRow(key,"film",120000,1200000,null,"in_progress",false,10,1);head=1
        assertTrue(cloud.sync().isSuccess)
        assertTrue(preferences.getAllRawEntries().isEmpty())
        val requestCount=requests.size
        preferences.registerCloudContents(listOf(template))
        val restored=preferences.getAllRawEntries().getValue(template.storageKey)
        assertEquals(120000L,restored.position);assertEquals(1200000L,restored.duration)
        assertEquals(movie.id,restored.contentId);assertEquals("Movie",restored.name)
        assertEquals(1,preferences.observeContinueWatching(1).first().size)
        assertEquals(requestCount,requests.size);assertTrue(journal().pending.isEmpty())
        assertNull(local("nas-1").data.first()[progressKey])
    }

    @Test fun downloadedEpisodeCanResolveAfterItsHistoryMetadataArrivesAndKeepsRemoteHiding()=runBlocking {
        activate()
        val episode=com.jojotv.home.iptv.IptvItem("iptv:provider:episode:19","provider","19",
            com.jojotv.home.iptv.ContentType.EPISODE,"Episode",season=1,episode=2,seriesId="17")
        val template=requireNotNull(com.jojotv.home.iptv.iptvCloudProgressTemplate(episode,"iptv:provider:series:17"))
        val key=access.cloudContext()!!.contentKey(template.storageKey)
        rows[key]=CloudProgressRow(key,"episode",90000,900000,null,"in_progress",true,10,1);head=1
        assertTrue(cloud.sync().isSuccess)
        preferences.registerCloudContents(listOf(template))
        val restored=preferences.getAllRawEntries().getValue(template.storageKey)
        assertEquals(90000L,restored.position);assertEquals(1,restored.season);assertEquals(2,restored.episode)
        assertTrue(preferences.observeContinueWatching(1).first().isEmpty())
        assertTrue(journal().pending.isEmpty())
    }

    @Test fun unchangedMetadataAndEmptyPollsLeaveVisibleProgressAndJournalUntouched()=runBlocking {
        activate();val saved=SupabaseProgressPilotTest.progress()
        preferences.saveProgress(saved);assertTrue(cloud.sync().isSuccess)
        cloud.registerContent(saved)
        // A harmless old formatting difference must not trigger a visible-cache rewrite.
        local("cloud").edit { it[progressKey]="\n"+it[progressKey]+"\n" }
        val before=local("cloud").data.first()
        repeat(12) {
            cloud.registerContent(saved.copy(position=0,duration=0,lastWatched=1))
            assertTrue(cloud.sync().isSuccess)
        }
        assertEquals(before,local("cloud").data.first())
        assertEquals(saved.position,preferences.getAllRawEntries().getValue(saved.storageKey).position)
        assertTrue(journal().pending.isEmpty())
    }

    @Test fun catalogueEvictionRetainsSavedPositionsAndTheirRemoteIdentity()=runBlocking {
        activate();val saved=SupabaseProgressPilotTest.progress()
        preferences.saveProgress(saved);assertTrue(cloud.sync().isSuccess)
        val unseen=(0..CLOUD_CATALOG_TEMPLATE_LIMIT+20).map { saved.copy(contentId="browse-$it",position=0,lastWatched=1) }
        cloud.registerContents(unseen)
        val cache=local("cloud").data.first()
        assertEquals(CLOUD_CATALOG_TEMPLATE_LIMIT,JsonParser.parseString(cache[templatesKey]).asJsonObject.size())
        assertEquals(CLOUD_CATALOG_TEMPLATE_LIMIT+1,JsonParser.parseString(cache[indexKey]).asJsonObject.size())
        val key=access.cloudContext()!!.contentKey(saved.storageKey)
        rows[key]=rows.getValue(key).copy(position_ms=180000,server_revision=++head)
        assertTrue(cloud.sync().isSuccess)
        assertEquals(180000L,preferences.getAllRawEntries().getValue(saved.storageKey).position)
        assertTrue(journal().pending.isEmpty());assertNull(local("nas-1").data.first()[progressKey])
    }

    @Test fun oversizedLegacyCatalogueIsTrimmedWithoutLosingAnUndeliveredCheckpoint()=runBlocking {
        activate();val saved=SupabaseProgressPilotTest.progress()
        preferences.saveProgress(saved)
        val pending=journal().pending.single()
        val legacy=(0..CLOUD_CATALOG_TEMPLATE_LIMIT*3).associate { i ->
            val item=saved.copy(contentId="legacy-$i",description="Synopsis ".repeat(100),position=0,lastWatched=1)
            item.storageKey to item
        }
        val oldIndex=legacy.map { (key,_) -> access.cloudContext()!!.contentKey(key) to key }.toMap() +
            (access.cloudContext()!!.contentKey(saved.storageKey) to saved.storageKey)
        local("cloud").edit { it[templatesKey]=gson.toJson(legacy);it[indexKey]=gson.toJson(oldIndex) }
        loseWriteResponse=true
        assertTrue(cloud.sync().isFailure)
        val after=local("cloud").data.first()
        assertEquals(CLOUD_CATALOG_TEMPLATE_LIMIT,JsonParser.parseString(after[templatesKey]).asJsonObject.size())
        assertEquals(saved.position,preferences.getAllRawEntries().getValue(saved.storageKey).position)
        assertEquals(pending,journal().pending.single())
        assertTrue(cloud.sync(force=true).isSuccess)
        assertTrue(journal().pending.isEmpty());assertEquals(1,rows.size)
    }

    @Test fun receivedRowsResolveBeforeAnOversizedIncomingCatalogueIsEvicted()=runBlocking {
        activate();val first=SupabaseProgressPilotTest.progress().copy(contentId="first-received")
        val key=access.cloudContext()!!.contentKey(first.storageKey)
        rows[key]=CloudProgressRow(key,"film",123456,900000,null,"in_progress",false,10,1);head=1
        assertTrue(cloud.sync().isSuccess)
        val batch=listOf(first)+(0..CLOUD_CATALOG_TEMPLATE_LIMIT+20).map { first.copy(contentId="new-$it") }
        cloud.registerContents(batch)
        assertEquals(123456L,preferences.getAllRawEntries().getValue(first.storageKey).position)
        assertTrue(journal().pending.isEmpty())
        assertEquals(CLOUD_CATALOG_TEMPLATE_LIMIT,JsonParser.parseString(local("cloud").data.first()[templatesKey]).asJsonObject.size())
    }

    @Test fun evictedUnplayedContentCanStillResolveItsExactResumeOnDemand()=runBlocking {
        activate();val first=SupabaseProgressPilotTest.progress().copy(contentId="evicted")
        cloud.registerContents(listOf(first)+(0..CLOUD_CATALOG_TEMPLATE_LIMIT).map { first.copy(contentId="other-$it") })
        assertFalse(JsonParser.parseString(local("cloud").data.first()[templatesKey]).asJsonObject.has(first.storageKey))
        val key=access.cloudContext()!!.contentKey(first.storageKey)
        rows[key]=CloudProgressRow(key,"film",70000,900000,null,"in_progress",false,10,1);head=1
        cloud.refreshContent(first)
        assertEquals(70000L,preferences.getAllRawEntries().getValue(first.storageKey).position)
        assertTrue(journal().pending.isEmpty())
    }

    @Test fun metadataUpdatesKeepThePendingLocalCheckpointAndDoNotCreateASecondOperation()=runBlocking {
        activate();val saved=SupabaseProgressPilotTest.progress()
        preferences.saveProgress(saved);val before=journal()
        cloud.registerContent(saved.copy(name="New title",poster="new-poster",position=0,duration=0,lastWatched=1))
        val after=preferences.getAllRawEntries().getValue(saved.storageKey)
        assertEquals(saved.position,after.position);assertEquals(saved.duration,after.duration)
        assertEquals(saved.lastWatched,after.lastWatched);assertEquals("New title",after.name)
        assertEquals("new-poster",after.poster);assertEquals(before,journal())
        assertTrue(requests.isEmpty())
    }

    @Test fun remoteNasMergeIsRejectedInCloudMode()=runBlocking {
        activate();val old=SupabaseProgressPilotTest.progress()
        assertTrue(runCatching { preferences.mergeRemoteEntries(mapOf(old.storageKey to old)) }.isFailure)
        assertTrue(preferences.getAllRawEntries().isEmpty());assertTrue(journal().pending.isEmpty())
    }

    @Test fun technicalWireFieldsExcludeLocalMetadataAndKeepExplicitNullablePercent()=runBlocking {
        activate();preferences.saveProgress(SupabaseProgressPilotTest.progress())
        assertTrue(cloud.sync().isSuccess)
        val wire=requests.first { it.url.endsWith("progress_sync_write_v2") }.body
        assertTrue(wire.contains("\"progress_percent\":null"))
        for(secret in listOf("Private title","private-content","private-url","poster","addonBaseUrl")) assertFalse(wire.contains(secret))
    }

    @Test fun staleRevisionCreatesAnExplicitConflictAndRetainsProposal()=runBlocking {
        activate();val saved=SupabaseProgressPilotTest.progress();preferences.saveProgress(saved)
        val key=access.cloudContext()!!.contentKey(saved.storageKey)
        rows[key]=CloudProgressRow(key,"film",200,1000,null,"in_progress",false,2,1);head=1
        assertTrue(cloud.sync().isSuccess)
        assertTrue(journal().pending.isEmpty());assertEquals(1,journal().conflicts.size)
        assertEquals(200L,preferences.getAllRawEntries()[saved.storageKey]?.position)
        preferences.saveProgress(saved.copy(position=500,lastWatched=2000))
        assertTrue(journal().pending.isEmpty())
        cloud.resolveFirstConflict(true)
        assertEquals(1L,journal().pending.single().expected_revision)
        assertTrue(cloud.sync().isSuccess)
        assertEquals(500L,rows[key]?.position_ms)
    }

    @Test fun hidingResumeKeepsCompletedEpisodeStateAndDoesNotDeleteItsProgress()=runBlocking {
        activate();val saved=SupabaseProgressPilotTest.progress().copy(position=10000,duration=10000,progressPercent=100f)
        preferences.saveProgress(saved);assertTrue(cloud.sync().isSuccess)
        preferences.removeProgress(saved.contentId)
        assertTrue(preferences.observeContinueWatching(1).first().isEmpty())
        assertTrue(preferences.getAllRawEntries()[saved.storageKey]!!.isCompleted())
        assertTrue(cloud.sync().isSuccess)
        assertTrue(rows.values.single().resume_hidden)
        assertEquals("completed",rows.values.single().watch_state)
        assertTrue(preferences.observeContinueWatching(1).first().isEmpty())
    }

    @Test fun aMaskChangeEmitsACatalogueSnapshotEvenWhenPlaybackHasNotChanged()=runBlocking {
        activate();val saved=SupabaseProgressPilotTest.progress()
        preferences.saveProgress(saved)
        val initial=preferences.getHomeProgressSnapshot(1)
        assertEquals(saved,initial.first.single())
        assertTrue(initial.second.isEmpty())
        val masked=async(start=CoroutineStart.UNDISPATCHED) {
            withTimeout(3000) { preferences.observeHomeProgressSnapshot(1).first { saved.storageKey in it.second } }
        }
        cloud.setResumeHidden(saved.contentId,true)
        val changed=masked.await()
        assertSame(initial.first,changed.first)
        assertEquals(saved,preferences.allRawProgress.first().single())
        assertTrue(com.jojotv.home.iptv.IptvStore.visibleVodProgress(changed.first,changed.second).isEmpty())
        cloud.setResumeHidden(saved.contentId,false)
        val restored=preferences.getHomeProgressSnapshot(1)
        assertEquals(listOf(saved),com.jojotv.home.iptv.IptvStore.visibleVodProgress(restored.first,restored.second))
    }

    @Test fun nasPinnedLegacyMutationCannotEnterCloudCache()=runBlocking {
        activate();val legacy=SupabaseProgressPilotTest.progress()
        val result=runCatching { kotlinx.coroutines.withContext(NasProgressMutation) { preferences.saveProgress(legacy) } }
        assertTrue(result.isFailure)
        assertTrue(preferences.getAllRawEntries().isEmpty());assertTrue(journal().pending.isEmpty())
    }

    @Test fun monotonicCaptureOrderRejectsDelayedCallbacksAndAllowsRewindWithSmallerWallClock()=runBlocking {
        activate();val sample=SupabaseProgressPilotTest.progress()
        kotlinx.coroutines.withContext(PilotPlaybackCheckpoint(2000,200)) {
            preferences.saveProgress(sample.copy(position=50000,lastWatched=2000))
        }
        kotlinx.coroutines.withContext(PilotPlaybackCheckpoint(9000,100)) {
            preferences.saveProgress(sample.copy(position=10000,lastWatched=9000))
        }
        assertEquals(50000L,preferences.getAllRawEntries()[sample.storageKey]?.position)
        kotlinx.coroutines.withContext(PilotPlaybackCheckpoint(1,300)) {
            preferences.saveProgress(sample.copy(position=5000,lastWatched=1))
        }
        assertEquals(5000L,preferences.getAllRawEntries()[sample.storageKey]?.position)
        assertEquals(5000L,journal().pending.single().payload.position_ms)
    }

    @Test fun failedDeliveryUsesBackoffWithoutCreatingAdditionalRequests()=runBlocking {
        activate();preferences.saveProgress(SupabaseProgressPilotTest.progress());loseWriteResponse=true
        assertTrue(cloud.sync().isFailure);val count=requests.size
        assertTrue(config.value.nextAttemptMs>System.currentTimeMillis())
        assertTrue(cloud.sync().isFailure);assertEquals(count,requests.size)
        assertTrue(cloud.sync(force=true).isSuccess)
    }

    @Test fun namespaceSeparatesOwnerProfileDeviceAndKeyGenerationButRetainsCacheAfterReactivation() {
        activate();val original=config.value;val key=cloudNamespace(original)
        assertEquals(key,cloudNamespace(original.copy(cloudActivationId=UUID.randomUUID().toString())))
        assertNotEquals(key,cloudNamespace(original.copy(cloudKeyEpoch=UUID.randomUUID().toString())))
        assertNotEquals(key,cloudNamespace(original.copy(scope=bound.copy(deviceId=bound.deviceB))))
        assertNotEquals(key,cloudNamespace(original.copy(scope=bound.copy(localProfileInstanceId=UUID.randomUUID().toString()))))
        assertNotEquals(key,cloudNamespace(original.copy(scope=bound.copy(ownerId=UUID.randomUUID().toString()))))
        assertNotEquals(key,cloudNamespace(original.copy(scope=bound.copy(profileBindingId=UUID.randomUUID().toString()))))
    }

    @Test fun requestedResumeIsFetchedEvenWhenItIsOutsideFirstPage()=runBlocking {
        activate();val saved=SupabaseProgressPilotTest.progress()
        for(i in 1..201) {
            val key=String.format("%064x",i)
            rows[key]=CloudProgressRow(key,"file",1,10,null,"in_progress",false,1,i.toLong())
        }
        val target=access.cloudContext()!!.contentKey(saved.storageKey)
        rows[target]=CloudProgressRow(target,"film",4321,10000,null,"in_progress",false,1,202);head=202
        preferences.registerCloudContent(saved) // The local catalogue supplies its provider identity, never its old position.
        preferences.prepareCloudResume(saved.contentId)
        assertEquals(4321L,preferences.getAllRawEntries()[saved.storageKey]?.position)
        assertEquals(200L,journal().cursor) // The targeted point must not skip the second page.
        assertTrue(journal().pending.isEmpty())
    }

    @Test fun fileUnwatchedResetsCloudPositionRatherThanOnlyHidingItAndLeavesNasUntouched()=runBlocking {
        val old=SupabaseProgressPilotTest.progress()
        preferences.saveProgress(old);val nas=local("nas-1").data.first()
        activate()
        val path="smb://private-server/private-video.mkv"
        val id=com.nuvio.tv.data.freebox.freeboxContentIdForFile(path,100L,200L)
        val file=old.copy(contentId=id,videoId=id,contentType="freebox",position=old.duration,progressPercent=100f)
        preferences.saveProgress(file)
        preferences.setCloudResumeHidden(id,true)
        preferences.resetCloudFileProgress(path)
        val reset=preferences.getAllRawEntries()[file.storageKey]!!
        assertEquals(0L,reset.position)
        assertFalse(reset.isCompleted())
        val row=journal().pending.single().payload
        assertEquals("unseen",row.watch_state)
        assertFalse(row.resume_hidden)
        assertEquals(0L,row.position_ms)
        kotlinx.coroutines.withContext(PilotPlaybackCheckpoint(9000,999)) {
            preferences.saveProgress(file.copy(position=5000,lastWatched=9000))
        }
        assertEquals(0L,preferences.getAllRawEntries()[file.storageKey]?.position)
        assertEquals("unseen",journal().pending.single().payload.watch_state)
        assertEquals(nas,local("nas-1").data.first())
        assertTrue(requests.isEmpty())
        assertFalse(gson.toJson(row).contains(path))
    }

    @Test fun malformedJournalFailsWithoutResettingSequenceOrErasingTheVisibleCache()=runBlocking {
        activate();val saved=SupabaseProgressPilotTest.progress();preferences.saveProgress(saved)
        val before=local("cloud").data.first()[progressKey]
        for (corrupt in listOf("null","{}","[]")) {
            local("cloud").edit { it[journalKey]=corrupt }
            assertTrue(runCatching { preferences.saveProgress(saved.copy(position=20000,lastWatched=2000)) }.isFailure)
            assertEquals(before,local("cloud").data.first()[progressKey])
            assertEquals(corrupt,local("cloud").data.first()[journalKey])
            assertEquals(PilotMode.SUPABASE_PROGRESS,config.value.mode)
        }
    }
}
