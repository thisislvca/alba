package dev.mela.protocol.photos

import dev.mela.engine.model.*
import dev.mela.protocol.account.*
import dev.mela.protocol.network.*
import java.io.*
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class SharedAlbumsCatalogTest {
    private fun session(dsid: String = "123") = AppleSessionSnapshot("test@example.com", "client", "IT", "session", "token", null,
        dsid, mapOf("ckdatabasews" to "https://p01-ckdatabasews.icloud.com", "sharedstreams" to "https://p01-sharedstreams.icloud.com"), emptyList())
    private fun source(
        t: Transport,
        journal: Journal = Journal(),
        provider: () -> AppleSessionSnapshot = { session() },
        onSessionRejected: suspend (AppleSessionSnapshot) -> Unit = {},
    ) = LiveICloudCatalogSource(
        transport = t,
        sessionProvider = provider,
        isAuthorized = { true },
        sharedJournal = journal,
        onSessionRejected = onSessionRejected,
    )

    @Test fun onlySharedAuthenticationRejectionExpiresTheAccount() = runBlocking {
        for (code in listOf(401, 403)) {
            val t = Transport().apply { responseCode = code }
            var rejected: AppleSessionSnapshot? = null
            val error = runCatching {
                source(t, onSessionRejected = { rejected = it }).sharedAlbums()
            }.exceptionOrNull()

            assertTrue(error is dev.mela.protocol.auth.AppleProtocolException)
            assertEquals(
                if (code == 401) dev.mela.protocol.auth.AppleProtocolError.SESSION_EXPIRED
                else dev.mela.protocol.auth.AppleProtocolError.PHOTOS_UNAVAILABLE,
                (error as dev.mela.protocol.auth.AppleProtocolException).error,
            )
            assertEquals(if (code == 401) session() else null, rejected)
        }
    }

    @Test fun scopesDuplicateNamesAndRecordIdsAcrossLegacyAndBothCloudKitDatabases() = runBlocking {
        val t = Transport(); val s = source(t)
        val snapshot = s.sharedAlbums()
        assertEquals(3, snapshot.collections.collections.size)
        assertEquals(1, snapshot.collections.collections.map { it.name }.distinct().size)
        assertEquals(3, snapshot.records.map { it.id }.distinct().size)
        assertTrue(snapshot.records.all { SharedMediaIdentity.isShared(it.id) })
        assertEquals(setOf(SharedAlbumRole.OWNER, SharedAlbumRole.VIEWER, SharedAlbumRole.CONTRIBUTOR), snapshot.collections.collections.map { it.shared!!.role }.toSet())
        val legacy = snapshot.records.first { it.fileName == "legacy.mp4" }
        assertEquals(MediaKind.VIDEO, legacy.kind); assertEquals("video/mp4", legacy.mimeType)
        assertEquals(1777777777000L, legacy.capturedAtEpochMillis)
        assertEquals(8733L, legacy.durationMillis); assertEquals(2000L, legacy.byteCount)
        assertEquals(3, snapshot.collections.members.values.sumOf { it.size })
    }

    @Test fun legacyLimitIsAnExclusiveEndAndAlbumShardComesFromApple() = runBlocking {
        val t = Transport().apply { legacyCount = 201 }
        val snapshot = source(t).sharedAlbums()
        assertEquals(203, snapshot.records.size)
        val requests = t.requests.filter { it.url.contains("webgetassets") }
        assertEquals(listOf("0" to "100", "100" to "200", "200" to "201"), requests.map {
            val b = Json.parseToJsonElement(it.body!!).jsonObject
            b["offset"]!!.jsonPrimitive.content to b["limit"]!!.jsonPrimitive.content
        })
        assertTrue(requests.all { it.url.startsWith("https://p55-sharedstreams.icloud.com/") })
    }

    @Test fun repeatedLegacyPagesFailWithoutPublishingAPartialAlbum() = runBlocking {
        val t = Transport().apply { legacyCount = 201; repeatPage = true }
        assertTrue(runCatching { source(t).sharedAlbums() }.isFailure)
    }

    @Test fun restartHydratesSharedMediaAndRefreshesExpiredResourcesOnce() = runBlocking {
        val t = Transport(); val first = source(t).sharedAlbums().records.first()
        t.expire = true
        val out = ByteArrayOutputStream()
        source(t).writePreview(first.id, out)
        assertEquals("image", out.toString()); assertEquals(2, t.streams)
        assertFalse(t.requests.any { it.body.orEmpty().contains("PrimarySync") && it.url.contains("records/lookup") })
    }

    @Test fun accountSwitchRejectsCachedMediaWithoutNetworkOrBytes() = runBlocking {
        var current = session(); val t = Transport(); val s = source(t, provider = { current })
        val id = s.sharedAlbums().records.first().id
        current = session("999"); t.requests.clear()
        assertTrue(runCatching { s.writeOriginal(id, ByteArrayOutputStream()) }.isFailure)
        assertTrue(t.requests.isEmpty()); assertEquals(0, t.streams)
    }

    @Test fun creationRecoversLostShareResponseWithoutCreatingAnotherZone() = runBlocking {
        val t = Transport().apply { loseShareResponse = true }; val journal = Journal(); val operation = UUID.randomUUID().toString()
        assertTrue(runCatching { source(t, journal).createSharedAlbum("New test", operation) }.isFailure)
        val album = source(t, journal).createSharedAlbum("New test", UUID.randomUUID().toString())
        assertEquals("New test", album.name); assertEquals(SharedAlbumGeneration.MODERN, album.shared!!.generation)
        assertEquals(1, t.zoneCreates); assertEquals(1, t.shareCreates)
        assertTrue(t.requests.filter { it.url.contains("modify") }.all { it.oneShot })
        assertEquals("NONE", t.createdShare!!["publicPermission"]!!.jsonPrimitive.content)
        assertEquals(true, t.createdShare!!["denyAccessRequests"]!!.jsonPrimitive.boolean)
    }

    @Test fun viewerCannotContributeEvenWhenInvokedOutsideTheUI() = runBlocking {
        val t = Transport(); val s = source(t)
        val album = s.sharedAlbums().collections.collections.first { it.shared!!.role == SharedAlbumRole.VIEWER }
        val personal = "icloud:${s.accountLabel}:asset"
        assertTrue(runCatching { s.contributeToSharedAlbum(album.id, listOf(personal), UUID.randomUUID().toString()) }.isFailure)
        assertEquals(0, t.copyStarts)
    }

    @Test fun copyResumesPollingAndPostAfterLostResponseWithoutRepeatingCopy() = runBlocking {
        val t = Transport().apply { losePostResponse = true }; val journal = Journal(); val s = source(t, journal)
        val album = s.sharedAlbums().collections.collections.first { it.shared!!.generation == SharedAlbumGeneration.MODERN && it.shared!!.role == SharedAlbumRole.OWNER }
        val ids = listOf("icloud:${s.accountLabel}:asset")
        assertTrue(runCatching { s.contributeToSharedAlbum(album.id, ids, UUID.randomUUID().toString()) }.isFailure)
        source(t, journal).contributeToSharedAlbum(album.id, ids, UUID.randomUUID().toString())
        assertEquals(1, t.copyStarts); assertEquals(1, t.postCreates)
        assertTrue(t.requests.first { it.url.contains("copy/start") }.oneShot)
    }

    @Test fun ambiguousCopyStartIsNeverBlindlyReplayed() = runBlocking {
        val t = Transport().apply { loseCopyResponse = true }; val journal = Journal(); val s = source(t, journal)
        val album = s.sharedAlbums().collections.collections.first { it.shared!!.generation == SharedAlbumGeneration.MODERN && it.shared!!.role == SharedAlbumRole.OWNER }
        val ids = listOf("icloud:${s.accountLabel}:asset")
        repeat(2) { assertTrue(runCatching { source(t, journal).contributeToSharedAlbum(album.id, ids, UUID.randomUUID().toString()) }.isFailure) }
        assertEquals(1, t.copyStarts)
    }

    @Test fun legacyUploadUsesOneReceiptAndCanResumePollingAcrossRestart() = runBlocking {
        val t = Transport().apply { legacyContributes = true; loseLegacyStatus = true }; val journal = Journal(); val s = source(t, journal)
        val album = s.sharedAlbums().collections.collections.first { it.shared!!.generation == SharedAlbumGeneration.LEGACY }
        val operation = UUID.randomUUID().toString()
        fun bytes() = object : OneShotUploadSource {
            override val byteCount = 4L; override val sha256Hex = "sha"; override fun openOnce() = ByteArrayInputStream("jpeg".toByteArray())
        }
        assertTrue(runCatching { s.uploadSharedPhoto(album.id, "test.jpg", bytes(), operation) }.isFailure)
        source(t, journal).uploadSharedPhoto(album.id, "test.jpg", bytes(), UUID.randomUUID().toString())
        assertEquals(1, t.rawUploads); assertEquals(1, t.legacyPuts)
        assertTrue(t.requests.first { it.url.contains("webputasset") }.oneShot)
    }

    @Test fun lostLegacyPublishIsRecoveredByContentAfterUiOperationIdIsLost() = runBlocking {
        val t = Transport().apply { legacyContributes = true; loseLegacyPut = true }
        val journal = Journal(); val source = source(t, journal)
        val album = source.sharedAlbums().collections.collections.first { it.shared!!.generation == SharedAlbumGeneration.LEGACY }
        fun bytes() = object : OneShotUploadSource {
            override val byteCount = 4L; override val sha256Hex = "sha"; override fun openOnce() = ByteArrayInputStream("jpeg".toByteArray())
        }
        assertTrue(runCatching { source.uploadSharedPhoto(album.id, "test.jpg", bytes(), UUID.randomUUID().toString()) }.isFailure)
        repeat(2) { source(t, journal).uploadSharedPhoto(album.id, "test.jpg", bytes(), UUID.randomUUID().toString()) }
        assertEquals(1, t.rawUploads); assertEquals(1, t.legacyPuts)
        // A completed copy removed later may be explicitly added again.
        t.registeredAsset = null
        source(t, journal).uploadSharedPhoto(album.id, "test.jpg", bytes(), UUID.randomUUID().toString())
        assertEquals(2, t.rawUploads); assertEquals(2, t.legacyPuts)
    }

    @Test fun legacyCreationRecoversByGuidAndKeepsTheAlbumPrivate() = runBlocking {
        val t = Transport().apply { loseLegacyCreate = true }; val journal = Journal()
        assertTrue(runCatching { source(t, journal).createSharedAlbum("Legacy test", UUID.randomUUID().toString(), SharedAlbumGeneration.LEGACY) }.isFailure)
        val album = source(t, journal).createSharedAlbum("Legacy test", UUID.randomUUID().toString(), SharedAlbumGeneration.LEGACY)
        assertEquals(SharedAlbumGeneration.LEGACY, album.shared!!.generation)
        assertEquals(SharedAlbumRole.OWNER, album.shared!!.role)
        assertEquals(1, t.legacyCreates)
        val request = t.requests.single { it.url.contains("/createalbum") }
        assertTrue(request.oneShot)
        assertEquals("0", Json.parseToJsonElement(request.body!!).jsonObject["attributes"]!!.jsonObject["ispublic"]!!.jsonPrimitive.content)
    }

    @Test fun rejectedForeignAlbumLocationNeverReceivesCookiesOrRequests() = runBlocking {
        val t = Transport().apply { albumHost = "https://evil.example" }
        assertTrue(runCatching { source(t).sharedAlbums() }.isFailure)
        assertFalse(t.requests.any { it.url.startsWith("https://evil") })
    }

    @Test fun commenterCannotContributeOrManageButCanDiscuss() = runBlocking {
        val t = Transport().apply { customRole = "commenter" }; val s = source(t)
        val snapshot = s.sharedAlbums()
        val album = snapshot.collections.collections.first { it.shared!!.role == SharedAlbumRole.COMMENTER }
        assertFalse(album.shared!!.canContribute); assertFalse(album.shared!!.canManage); assertTrue(album.shared!!.canComment)
        assertTrue(runCatching { s.contributeToSharedAlbum(album.id, listOf("icloud:${s.accountLabel}:asset"), UUID.randomUUID().toString()) }.isFailure)
        assertTrue(runCatching { s.changeSharedAlbum(album.id, SharedCommand(SharedAction.RENAME, value = "Bad"), UUID.randomUUID().toString()) }.isFailure)
        assertFalse(t.requests.any { it.oneShot })
    }
    @Test fun modernCommentHasEncryptedTextAndExactAssetReferenceAndRecoversLostResponse() = runBlocking {
        val t = Transport().apply { loseCommentResponse = true }; val j = Journal(); val s = source(t,j)
        val snap = s.sharedAlbums(); val album = snap.collections.collections.first { it.shared!!.role == SharedAlbumRole.OWNER }
        val media = snap.collections.members.getValue(album.id).single()
        val command = SharedCommand(SharedAction.COMMENT, media, "Hello family")
        assertTrue(runCatching { s.changeSharedAlbum(album.id, command, UUID.randomUUID().toString()) }.isFailure)
        source(t,j).changeSharedAlbum(album.id, command, UUID.randomUUID().toString())
        assertEquals(1, t.commentWrites)
        val fields = t.comments.values.single()["fields"]!!.jsonObject
        assertEquals("Hello family",fields["commentText"]!!.jsonObject["value"]!!.jsonPrimitive.content)
        assertTrue(fields["commentText"]!!.jsonObject["isEncrypted"]!!.jsonPrimitive.boolean)
        assertEquals("asset-0",fields["associatedAssetRef"]!!.jsonObject["value"]!!.jsonObject["recordName"]!!.jsonPrimitive.content)
    }
    @Test fun foreignMediaCannotBeUsedForACommentInAnotherAlbum() = runBlocking {
        val t = Transport(); val s = source(t); val snap = s.sharedAlbums()
        val owner = snap.collections.collections.first { it.shared!!.role == SharedAlbumRole.OWNER }
        val other = snap.records.first { !it.id.startsWith(owner.id) }
        assertTrue(runCatching { s.changeSharedAlbum(owner.id, SharedCommand(SharedAction.COMMENT, other.id, "wrong"), UUID.randomUUID().toString()) }.isFailure)
        assertEquals(0,t.commentWrites)
    }
    @Test fun legacyDiscussionUsesNumericTypesAndOwnerShard() = runBlocking {
        val t = Transport(); val s = source(t); val snap = s.sharedAlbums()
        val album = snap.collections.collections.first { it.shared!!.generation == SharedAlbumGeneration.LEGACY }
        val media = snap.collections.members.getValue(album.id).single()
        s.changeSharedAlbum(album.id, SharedCommand(SharedAction.COMMENT, media, "Test"), UUID.randomUUID().toString())
        val request = t.requests.last { it.url.contains("addcomment") }
        val body = Json.parseToJsonElement(request.body!!).jsonObject
        assertEquals("0",body["commenttype"]!!.jsonPrimitive.content)
        assertEquals("asset-0",body["assetguid"]!!.jsonPrimitive.content)
        val timestamp = body["commenttimestamp"]!!.jsonPrimitive.content
        assertTrue(timestamp.matches(Regex("[0-9]+\\.[0-9]{6}")))
        assertTrue(kotlin.math.abs(System.currentTimeMillis() - legacyCommentTime(timestamp)) < 5000)
        assertTrue(request.url.startsWith("https://p55-sharedstreams.icloud.com/other/")); assertTrue(request.oneShot)
    }
    @Test fun legacyDiscussionDecodesLiveFractionalAppleEpochAndPreservesPermissions() = runBlocking {
        val t = Transport().apply { legacyComments = Json.parseToJsonElement("""[
            {"commentposition":"0","commenttype":"0","commenttimestamp":"811934006.899000","comment":"Synthetic fixture","createdbyme":"1","candelete":"1"},
            {"commentposition":"1","commenttype":"1","commenttimestamp":"811934019.633000","createdbyme":"1","candelete":"1"},
            {"commentposition":"2","commenttype":"89","commenttimestamp":"811934019.633000"}
        ]""").jsonArray }
        val s = source(t); val snap = s.sharedAlbums()
        val album = snap.collections.collections.first { it.shared!!.generation == SharedAlbumGeneration.LEGACY }
        val comments = s.sharedDiscussion(snap.collections.members.getValue(album.id).single()).comments
        assertEquals(2, comments.size)
        assertEquals(1790241206899L, comments[0].timestamp)
        assertEquals(1790241219633L, comments[1].timestamp)
        assertTrue(comments.all { it.isMine && it.canDelete })
        assertTrue(comments[1].reaction)
        assertEquals("811934006.899000", legacyCommentTimestamp(comments[0].timestamp))
        assertEquals(978307200000L, legacyCommentTime("0"))
        assertEquals(0L, legacyCommentTime("NaN"))
        assertEquals(0L, legacyCommentTime("1e100"))
    }
    @Test fun lostLegacyCommentResponseIsNotReplayed() = runBlocking {
        val t = Transport().apply { loseLegacyCommentResponse = true }; val j = Journal(); val s = source(t,j)
        val snap = s.sharedAlbums(); val album = snap.collections.collections.first { it.shared!!.generation == SharedAlbumGeneration.LEGACY }
        val command = SharedCommand(SharedAction.COMMENT, snap.collections.members.getValue(album.id).single(), "Test")
        repeat(2) { assertTrue(runCatching { source(t,j).changeSharedAlbum(album.id,command,UUID.randomUUID().toString()) }.isFailure) }
        assertEquals(1,t.requests.count { it.url.contains("addcomment") })
    }
    @Test fun shareUpdatesUseChangeTagAndDoNotOverwriteUnrelatedProperties() = runBlocking {
        val t = Transport(); val s = source(t); val album = s.sharedAlbums().collections.collections.first { it.shared!!.role == SharedAlbumRole.OWNER }
        s.changeSharedAlbum(album.id,SharedCommand(SharedAction.RENAME,value="Renamed"),UUID.randomUUID().toString())
        val r = t.requests.last { it.url.contains("records/modify") }
        val op = Json.parseToJsonElement(r.body!!).jsonObject["operations"]!!.jsonArray.single().jsonObject
        assertEquals("update",op["operationType"]!!.jsonPrimitive.content)
        assertEquals("v1",op["record"]!!.jsonObject["recordChangeTag"]!!.jsonPrimitive.content)
        assertFalse(op["record"]!!.jsonObject.containsKey("participants"))
        assertTrue(r.oneShot)
    }
    @Test fun ownersCannotLeaveAndNoOneCanRemoveTheOwner() = runBlocking {
        val t = Transport(); val s = source(t); val album = s.sharedAlbums().collections.collections.first { it.shared!!.role == SharedAlbumRole.OWNER }
        assertTrue(runCatching { s.changeSharedAlbum(album.id,SharedCommand(SharedAction.LEAVE),UUID.randomUUID().toString()) }.isFailure)
        assertTrue(runCatching { s.changeSharedAlbum(album.id,SharedCommand(SharedAction.REMOVE_PARTICIPANT,"owner-participant"),UUID.randomUUID().toString()) }.isFailure)
        assertFalse(t.requests.any { it.oneShot })
    }
    @Test fun invitePersistsStableParticipantAndNeverReplaysLostResponse() = runBlocking {
        val t = Transport().apply { loseShareResponse = true }; val j = Journal(); val s = source(t,j)
        val album = s.sharedAlbums().collections.collections.first { it.shared!!.role == SharedAlbumRole.OWNER }
        val command = SharedCommand(SharedAction.INVITE,value="test@example.com")
        repeat(2) { assertTrue(runCatching { source(t,j).changeSharedAlbum(album.id,command,UUID.randomUUID().toString()) }.isFailure) }
        assertEquals(1,t.shareCreates)
        val participant = t.createdShare!!["participants"]!!.jsonArray.last().jsonObject
        assertEquals("contributor",participant["customRole"]!!.jsonPrimitive.content)
        assertTrue(participant["participantId"]!!.jsonPrimitive.content.isNotEmpty())
    }
    @Test fun modernSaveCopiesTowardPrimarySyncAndResumesKnownJob() = runBlocking {
        val t = Transport().apply { loseCopyStatus = true }; val j = Journal(); val s = source(t,j)
        val snap = s.sharedAlbums(); val album = snap.collections.collections.first { it.shared!!.role == SharedAlbumRole.OWNER }
        val command = SharedCommand(SharedAction.SAVE_TO_LIBRARY,snap.collections.members.getValue(album.id).single())
        assertTrue(runCatching { s.changeSharedAlbum(album.id,command,UUID.randomUUID().toString()) }.isFailure)
        source(t,j).changeSharedAlbum(album.id,command,UUID.randomUUID().toString())
        assertEquals(1,t.copyStarts)
        val request = t.requests.first { it.url.contains("copy/start") }
        val body = Json.parseToJsonElement(request.body!!).jsonObject
        assertEquals("cloudkit.zoneshare",body["sourceShareName"]!!.jsonPrimitive.content)
        assertEquals("PrimarySync",body["targetZoneID"]!!.jsonObject["zoneName"]!!.jsonPrimitive.content)
        assertFalse(body.containsKey("targetShareName"))
    }

    @Test fun disablingPublicAccessPreservesPeopleAndRotatesPublicParticipantIdentity() = runBlocking {
        val t = Transport().apply { publicShare = true }; val s = source(t)
        val album = s.sharedAlbums().collections.collections.first { it.shared!!.role == SharedAlbumRole.OWNER }
        s.changeSharedAlbum(album.id, SharedCommand(SharedAction.PUBLIC_ACCESS, enabled = false), UUID.randomUUID().toString())
        val record = t.createdShare!!
        assertEquals("NONE", record["publicPermission"]!!.jsonPrimitive.content)
        val people = record["participants"]!!.jsonArray.map { it.jsonObject }
        assertEquals("owner-participant",people.first()["participantId"]!!.jsonPrimitive.content)
        assertEquals("USER",people.last()["type"]!!.jsonPrimitive.content)
        assertNotEquals("public-person",people.last()["participantId"]!!.jsonPrimitive.content)
        assertEquals("contributor",people.last()["customRole"]!!.jsonPrimitive.content)
    }
    @Test fun enablingPublicAccessAlsoApprovesPendingRequests() = runBlocking {
        val t = Transport().apply { pendingRequest = true }; val s = source(t)
        val album = s.sharedAlbums().collections.collections.first { it.shared!!.role == SharedAlbumRole.OWNER }
        s.changeSharedAlbum(album.id, SharedCommand(SharedAction.PUBLIC_ACCESS, enabled = true), UUID.randomUUID().toString())
        val record = t.createdShare!!
        assertTrue(record["requesters"]!!.jsonArray.isEmpty())
        val guest = record["participants"]!!.jsonArray.last().jsonObject
        assertTrue(guest["isApprovedRequester"]!!.jsonPrimitive.boolean)
        assertEquals("waiting-user", guest["userIdentity"]!!.jsonObject["lookupInfo"]!!.jsonObject["userRecordName"]!!.jsonPrimitive.content)
    }
    @Test fun legacyContributorCanRemoveOnlyOwnPhotoAndDeletedCommentsAreNotDisplayed() = runBlocking {
        val t = Transport().apply { legacyAuthor = "me" }; val s = source(t)
        val photo = s.sharedAlbums().records.first { it.fileName == "legacy.mp4" }
        assertTrue(s.sharedDiscussion(photo.id).canRemovePhoto)
        t.legacyAuthor = "someone-else"
        assertFalse(s.sharedDiscussion(photo.id).canRemovePhoto)
        assertTrue(runCatching { s.changeSharedAlbum(photo.id.substringBeforeLast(':'),SharedCommand(SharedAction.REMOVE_MEDIA,photo.id),UUID.randomUUID().toString()) }.isFailure)
        assertFalse(t.requests.any { it.url.contains("deleteassets") })
    }

    @Test fun legacyInvitationAcceptanceRecoversLostResponseWithoutResubscribing() = runBlocking {
        val t = Transport().apply { pendingLegacy = true; legacyJoined = false; loseInviteResponse = true }
        val j = Journal(); val s = source(t,j); val invitation = s.pendingSharedInvitations().single()
        assertEquals("Invited family",invitation.name); assertTrue(invitation.canDecline)
        assertTrue(runCatching { s.respondSharedInvitation(invitation.id,true,UUID.randomUUID().toString()) }.isFailure)
        source(t,j).respondSharedInvitation(invitation.id,true,UUID.randomUUID().toString())
        assertEquals(1,t.requests.count { it.url.toHttpUrl().encodedPath.endsWith("/subscribe") })
        assertTrue(s.pendingSharedInvitations().isEmpty())
    }
    @Test fun modernInvitationOnlyAcceptsAlbumLinksAndRecoversAcceptanceResponseLoss() = runBlocking {
        val t = Transport().apply { loseInviteResponse = true }; val j = Journal(); val s = source(t,j)
        val invitation = s.resolveSharedInvitation("https://www.icloud.com/photos/#/sharedalbums/sc,TestLink/")
        assertEquals(SharedAlbumGeneration.MODERN,invitation.generation)
        assertFalse(t.requests.any { it.oneShot })
        assertTrue(runCatching { s.respondSharedInvitation(invitation.id,true,UUID.randomUUID().toString()) }.isFailure)
        source(t,j).respondSharedInvitation(invitation.id,true,UUID.randomUUID().toString())
        assertEquals(1,t.requests.count { it.url.contains("/public/records/accept") })
        val before = t.requests.size
        assertTrue(runCatching { s.resolveSharedInvitation("https://evil.example/photos/#/sharedalbums/sc,TestLink/") }.isFailure)
        assertEquals(before,t.requests.size)
        assertTrue(runCatching { s.respondSharedInvitation(invitation.id.replace("invitation:${s.accountLabel}:","invitation:someone-else:"),true,UUID.randomUUID().toString()) }.isFailure)
        assertEquals(before,t.requests.size)
    }
    @Test fun acceptsFirstPartyCopiedPhotosHostButRejectsLookalikesAndNonstandardPorts() = runBlocking {
        val t = Transport(); val s = source(t)
        val invitation = s.resolveSharedInvitation("https://photos.icloud.com/shared/album/ObservedShortGUID")
        assertEquals(SharedAlbumGeneration.MODERN, invitation.generation)
        val body = Json.parseToJsonElement(t.requests.last().body!!).jsonObject
        assertEquals("ObservedShortGUID", body["shortGUIDs"]!!.jsonArray.single().jsonObject["value"]!!.jsonPrimitive.content)
        val before = t.requests.size
        for (url in listOf("https://photos.icloud.com.attacker.test/shared/album/token", "https://photos.icloud.com:444/shared/album/token", "https://user@photos.icloud.com/shared/album/token")) {
            assertTrue(runCatching { s.resolveSharedInvitation(url) }.isFailure)
        }
        assertEquals(before, t.requests.size)
    }
    @Test fun joiningAnAlreadyAcceptedAlbumNeverResendsAcceptance() = runBlocking {
        val t = Transport().apply { acceptedModern = true }; val s = source(t)
        val invitation = s.resolveSharedInvitation("https://photos.icloud.com/shared/album/OwnedAlbum")
        s.respondSharedInvitation(invitation.id, true, UUID.randomUUID().toString())
        assertFalse(t.requests.any { it.oneShot })
    }
    @Test fun declineOnlyTargetsPendingLegacyInvitations() = runBlocking {
        val t = Transport().apply { pendingLegacy = true; legacyJoined = false }; val s = source(t)
        val invitation = s.pendingSharedInvitations().single()
        s.respondSharedInvitation(invitation.id,false,UUID.randomUUID().toString())
        assertTrue(s.pendingSharedInvitations().isEmpty())
        assertEquals(1,t.requests.count { it.url.toHttpUrl().encodedPath.endsWith("/unsubscribe") })
    }

    @Test fun oneTimeLinkIsPrivateAndLostResponseDoesNotCreateAnotherInvitation() = runBlocking {
        val t = Transport().apply { reflectShareWrites = true; loseShareResponse = true }; val j = Journal(); val s = source(t,j)
        val album = s.sharedAlbums().collections.collections.first { it.shared!!.role == SharedAlbumRole.OWNER }
        val command = SharedCommand(SharedAction.CREATE_INVITE_LINK)
        assertTrue(runCatching { s.changeSharedAlbum(album.id,command,UUID.randomUUID().toString()) }.isFailure)
        source(t,j).changeSharedAlbum(album.id,command,UUID.randomUUID().toString())
        assertEquals(1,t.shareCreates)
        val record = t.createdShare!!; assertFalse(record.containsKey("publicPermission"))
        assertEquals("contributor",record["participants"]!!.jsonArray.last().jsonObject["customRole"]!!.jsonPrimitive.content)
        assertEquals("https://photos.icloud.com/shared/album/OnlyLink",s.sharedManagement(album.id).invitationLinks.single())
        s.changeSharedAlbum(album.id,SharedCommand(SharedAction.REVOKE_INVITE_LINKS),UUID.randomUUID().toString())
        assertTrue(s.sharedManagement(album.id).invitationLinks.isEmpty())
        assertEquals("owner-participant",t.createdShare!!["participants"]!!.jsonArray.single().jsonObject["participantId"]!!.jsonPrimitive.content)
    }

    @Test fun denyRecordsBlockAndUnblockPreservesOtherPeopleAndRecoversLostReply() = runBlocking {
        val t = Transport().apply { pendingRequest = true; reflectShareWrites = true }; val j = Journal(); val s = source(t,j)
        val album = s.sharedAlbums().collections.collections.first { it.shared!!.role == SharedAlbumRole.OWNER }
        s.changeSharedAlbum(album.id, SharedCommand(SharedAction.DENY_REQUEST, "waiting-user"), UUID.randomUUID().toString())
        assertEquals("waiting-user", s.sharedManagement(album.id).blocked.single().id)
        assertTrue(s.sharedManagement(album.id).requests.isEmpty())
        t.loseShareResponse = true
        val command = SharedCommand(SharedAction.UNBLOCK, "waiting-user")
        assertTrue(runCatching { s.changeSharedAlbum(album.id, command, UUID.randomUUID().toString()) }.isFailure)
        val writes = t.shareCreates
        source(t,j).changeSharedAlbum(album.id, command, UUID.randomUUID().toString())
        assertEquals(writes, t.shareCreates)
        assertTrue(s.sharedManagement(album.id).blocked.isEmpty())
        assertEquals("owner-participant", s.sharedManagement(album.id).participants.single().id)
        val nonOwner = s.sharedAlbums().collections.collections.first { it.shared!!.role == SharedAlbumRole.CONTRIBUTOR }
        assertTrue(runCatching { s.changeSharedAlbum(nonOwner.id, command, UUID.randomUUID().toString()) }.isFailure)
        assertEquals(writes, t.shareCreates)
    }
    @Test fun activityUsesRankPagingAndPostCommentsHaveSeparateReferenceAndRecovery() = runBlocking {
        val t = Transport().apply { activityCount = 26 }; val j = Journal(); val s = source(t,j)
        val album = s.sharedAlbums().collections.collections.first { it.shared!!.role == SharedAlbumRole.OWNER }
        val first = s.sharedActivity(album.id, 0); val second = s.sharedActivity(album.id, first.nextRank!!)
        assertEquals(25, first.posts.size); assertEquals(1, second.posts.size); assertNull(second.nextRank)
        assertTrue(first.posts.none { a -> second.posts.any { it.id == a.id } })
        val post = first.posts.first()
        val command = SharedCommand(SharedAction.POST_COMMENT, post.id, "A whole post")
        t.loseCommentResponse = true
        assertTrue(runCatching { s.changeSharedAlbum(album.id,command,UUID.randomUUID().toString()) }.isFailure)
        source(t,j).changeSharedAlbum(album.id,command,UUID.randomUUID().toString())
        assertEquals(1,t.commentWrites)
        val fields = t.comments.values.single()["fields"]!!.jsonObject
        assertNotNull(fields["postRef"]); assertNull(fields["associatedAssetRef"])
        assertEquals("CPLPost-0", fields["postRef"]!!.jsonObject["value"]!!.jsonObject["recordName"]!!.jsonPrimitive.content)
        val another = s.sharedAlbums().collections.collections.first { it.shared!!.role == SharedAlbumRole.CONTRIBUTOR }
        assertTrue(runCatching { s.changeSharedAlbum(another.id,command,UUID.randomUUID().toString()) }.isFailure)
        assertEquals(1,t.commentWrites)
        assertEquals(1,s.sharedPostPhotos(post.id).size)
        t.splitPostPreview = true
        assertEquals(2,s.sharedPostPhotos(post.id).size)
    }

    private class Journal : UploadJournal {
        val values = mutableMapOf<Pair<AccountBinding, String>, UploadCheckpoint>()
        override suspend fun load(binding: AccountBinding, attempt: String) = values[binding to attempt]
        override suspend fun save(binding: AccountBinding, attempt: String, checkpoint: UploadCheckpoint) { values[binding to attempt] = checkpoint }
    }
    private class Transport : AppleHttpTransport {
        val requests = mutableListOf<AppleHttpRequest>()
        var customRole = "contributor"; var loseCommentResponse = false; var loseLegacyCommentResponse = false; var loseCopyStatus = false; var commentWrites = 0
        val comments = mutableMapOf<String,JsonObject>()
        var legacyComments = JsonArray(emptyList())
        var legacyCount = 1; var repeatPage = false; var legacyContributes = false; var albumHost = "https://p55-sharedstreams.icloud.com"
        var loseLegacyPut = false; var registeredAsset: String? = null; var createdLegacy: JsonObject? = null; var legacyCreates = 0; var loseLegacyCreate = false
        var expire = false; var streams = 0; var rawUploads = 0; var legacyPuts = 0
        var loseShareResponse = false; var loseCopyResponse = false; var losePostResponse = false; var loseLegacyStatus = false
        var zoneCreates = 0; var shareCreates = 0; var copyStarts = 0; var postCreates = 0
        var createdZone: JsonObject? = null; var createdShare: JsonObject? = null; var createdPost: JsonObject? = null
        override val sessionHeaders = AppleSessionHeaders()
        private fun zone(name: String, owner: String = "owner") = buildJsonObject { put("zoneName", name); put("ownerRecordName", owner); put("zoneType", "REGULAR_CUSTOM_ZONE") }
        var activityCount = 0
        var splitPostPreview = false
        private fun post(index: Int) = buildJsonObject {
            put("recordName", "CPLPost-$index"); put("recordType", "CPLPost")
            putJsonObject("fields") { putJsonObject("postTimestamp") { put("value", 1790205441441L - index) } }
            putJsonObject("created") { put("userRecordName", "owner") }
        }
        var reflectShareWrites = false
        var pendingLegacy = false; var legacyJoined = true; var acceptedModern = false; var loseInviteResponse = false
        var publicShare = false; var pendingRequest = false; var legacyAuthor = "other"
        var responseCode = 200
        private fun share(scope: String): JsonObject { val base = Json.parseToJsonElement("""{"recordName":"cloudkit.zoneshare","recordType":"cloudkit.share","fields":{"cloudkit.title":{"value":"Same name"}},"recordChangeTag":"v1","participants":[{"participantId":"owner-participant","type":"OWNER","userIdentity":{"userRecordName":"owner"}}],"currentUserParticipant":{"type":"${if(scope == "private") "OWNER" else "USER"}","acceptanceStatus":"ACCEPTED","permission":"READ_WRITE","customRole":"$customRole"}}""").jsonObject
            return JsonObject(base + buildJsonObject {
                put("publicPermission",if(publicShare) "READ_WRITE" else "NONE")
                if(publicShare) put("participants",JsonArray(base["participants"]!!.jsonArray + buildJsonObject {
                    put("participantId","public-person"); put("type","PUBLIC_USER"); put("permission","READ_WRITE")
                }))
                if(pendingRequest) putJsonArray("requesters") { add(buildJsonObject { putJsonObject("requesterInformation") { put("userRecordName","waiting-user") } }) }
            } + (if(reflectShareWrites) createdShare.orEmpty() else emptyMap()))
        }

        private fun pair(index: Int, legacy: Boolean): List<JsonObject> {
            val date = if (legacy) "" else "\"assetDate\":{\"value\":1777777777000},"
            val file = if (legacy) "legacy.mp4" else "modern.jpg"
            val encoded = java.util.Base64.getEncoder().encodeToString(file.toByteArray())
            val assetName = if (legacy && index == 0) registeredAsset ?: "asset-$index" else "asset-$index"
            return listOf(
                Json.parseToJsonElement("""{"recordName":"master-$index","recordType":"CPLMaster","recordChangeTag":"m1","fields":{"filenameEnc":{"value":"$encoded","type":"BYTES"},"resOriginalFileType":{"value":"${if (legacy) "public.mpeg-4" else "public.jpeg"}"},"originalCreationDate":{"value":1777777777000},"resOriginalRes":{"value":{"downloadURL":"https://cws.icloud-content.com/original-$index","fileChecksum":"hash","size":0}},"resOriginalFileSize":{"value":2000},"resJPEGMedRes":{"value":{"downloadURL":"https://cws.icloud-content.com/preview-$index","fileChecksum":"preview"}}}}""").jsonObject,
                Json.parseToJsonElement("""{"recordName":"$assetName","recordType":"CPLAsset","recordChangeTag":"a1","fields":{$date"contributedBy":{"value":"$legacyAuthor"},"masterRef":{"value":{"recordName":"master-$index"}},"duration":{"value":8733}}}""").jsonObject)
        }
        override suspend fun execute(request: AppleHttpRequest): AppleHttpResponse {
            requests += request
            if (responseCode !in 200..299) return AppleHttpResponse(responseCode, emptyMap(), "{}")
            val path = request.url.toHttpUrl().encodedPath
            val body = Json.parseToJsonElement(request.body ?: "{}").jsonObject
            val scope = if ("/shared/" in path) "shared" else "private"
            val target = (body["zoneID"] as? JsonObject)?.get("zoneName")?.jsonPrimitive?.content
            val result: JsonElement = when {
                path.endsWith("webgetpendingalbums") -> buildJsonObject { putJsonArray("albums") { if(pendingLegacy) add(buildJsonObject { put("albumguid","legacy"); putJsonObject("attributes") { put("name","Invited family") } }) } }
                path.endsWith("/subscribe") -> {
                    pendingLegacy = false; legacyJoined = true
                    if(loseInviteResponse) { loseInviteResponse = false; throw IOException("lost invitation") }
                    buildJsonObject {}
                }
                path.endsWith("/unsubscribe") -> { pendingLegacy = false; legacyJoined = false; buildJsonObject {} }
                path.endsWith("records/resolve") || path.endsWith("records/accept") -> {
                    if(path.endsWith("records/accept")) { acceptedModern = true; if(loseInviteResponse) { loseInviteResponse = false; throw IOException("lost invitation") } }
                    buildJsonObject { putJsonArray("results") { add(buildJsonObject {
                        put("zoneID",zone("SharedCollection-invited"))
                        put("share",JsonObject(share("shared") + ("currentUserParticipant" to buildJsonObject { put("acceptanceStatus",if(acceptedModern) "ACCEPTED" else "INVITED") })))
                    }) } }
                }
                path.endsWith("webgetalbumslist") -> Json.parseToJsonElement("""{"albums":[{"albumguid":"legacy","ownerdsid":"other","albumlocation":"$albumHost/other/sharedstreams/","albumctag":"v1","sharingtype":"subscribed","iswebuploadsupported":"1","attributes":{"name":"Same name","allowcontributions":"${if(legacyContributes) 1 else 0}"}}]}""") .jsonObject.let { root -> JsonObject(root + ("albums" to JsonArray((if(legacyJoined) root["albums"]!!.jsonArray else emptyList()) + listOfNotNull(createdLegacy)))) }
                path.endsWith("createalbum") -> {
                    legacyCreates++
                    createdLegacy = buildJsonObject { put("albumguid", body.getValue("albumguid")); put("ownerdsid", "owner"); put("albumlocation", "$albumHost/owner/sharedstreams/"); put("sharingtype", "owned"); put("attributes", body.getValue("attributes")) }
                    if(loseLegacyCreate) { loseLegacyCreate = false; throw IOException("lost creation") }
                    buildJsonObject { put("albumguid", body.getValue("albumguid")) }
                }
                path.endsWith("webgetassetcount") -> buildJsonObject { put("albumassetcount", legacyCount) }
                path.endsWith("webgetassets") -> {
                    val start = body["offset"]!!.jsonPrimitive.content.toInt(); val end = body["limit"]!!.jsonPrimitive.content.toInt()
                    buildJsonObject { put("records", JsonArray((start until end).flatMap { pair(if(repeatPage) it % 100 else it, true) })) }
                }
                path.endsWith("changes/database") -> buildJsonObject { putJsonArray("zones") {
                    add(buildJsonObject { put("zoneID", zone("SharedCollection-$scope", if(scope == "private") "owner" else "other")) })
                    if(scope == "private") createdZone?.let { add(buildJsonObject { put("zoneID", it) }) }
                }; put("moreComing", false) }
                path.endsWith("zones/list") -> buildJsonObject { putJsonArray("zones") {
                    add(buildJsonObject { put("zoneID", zone("PrimarySync")) }); createdZone?.let { add(buildJsonObject { put("zoneID", it) }) }
                } }
                path.endsWith("zones/modify") -> {
                    zoneCreates++; createdZone = zone(body["operations"]!!.jsonArray.single().jsonObject["zone"]!!.jsonObject["zoneID"]!!.jsonObject["zoneName"]!!.jsonPrimitive.content)
                    buildJsonObject { putJsonArray("zones") { add(buildJsonObject { put("zoneID", createdZone!!) }) } }
                }
                path.endsWith("records/lookup") -> {
                    val name = body["records"]!!.jsonArray.single().jsonObject["recordName"]!!.jsonPrimitive.content
                    val record = if(name != "cloudkit.zoneshare") comments[name] ?: if (name.startsWith("CPLPost-") && activityCount > 0) post(name.removePrefix("CPLPost-").toInt()) else createdPost else if(target == createdZone?.get("zoneName")?.jsonPrimitive?.content) createdShare else share(scope)
                    buildJsonObject { putJsonArray("records") { add(record ?: buildJsonObject { put("serverErrorCode", "NOT_FOUND") }) } }
                }
                path.endsWith("records/modify") -> {
                    val record = body["operations"]!!.jsonArray.single().jsonObject["record"]!!.jsonObject
                    if(record["recordType"]?.jsonPrimitive?.content in setOf("CPLTextComment","CPLReact")) {
                        commentWrites++; comments[record["recordName"]!!.jsonPrimitive.content] = record
                        if(loseCommentResponse) { loseCommentResponse = false; throw IOException("lost comment") }
                    } else if(record["recordType"]!!.jsonPrimitive.content == "cloudkit.share") {
                        shareCreates++; createdShare = record
                        if(reflectShareWrites && record.containsKey("oneTimeStableUrlInfo")) {
                            val links = record["oneTimeStableUrlInfo"]!!.jsonObject["oneTimeLinks"]!!.jsonArray
                            createdShare = JsonObject(record + ("oneTimeStableUrlInfo" to buildJsonObject { put("oneTimeLinks",JsonArray(links.map { JsonObject(it.jsonObject + ("sharingLink" to JsonPrimitive("OnlyLink"))) })) }))
                        }
                        if(loseShareResponse) { loseShareResponse = false; throw IOException("lost response") }
                    } else { postCreates++; createdPost = record; if(losePostResponse) { losePostResponse = false; throw IOException("lost post") } }
                    buildJsonObject { put("records", JsonArray(listOf(record))) }
                }
                path.endsWith("copy/start") -> { copyStarts++; if(loseCopyResponse) throw IOException("lost copy result"); buildJsonObject { put("jobID", "copy-job") } }
                path.endsWith("copy/status") -> { if(loseCopyStatus) { loseCopyStatus=false; throw IOException("offline") }; buildJsonObject { put("status", "COMPLETED"); put("jobID", "copy-job") } }
                path.endsWith("webgetuploadurl") -> buildJsonObject { put("uploadurl", "https://cws.icloud-content.com/upload?a=1") }
                path.endsWith("webputasset") -> { legacyPuts++; registeredAsset = body["assetguid"]!!.jsonPrimitive.content; if(loseLegacyPut) { loseLegacyPut = false; throw IOException("lost publish") }; buildJsonObject { put("albumguid", body.getValue("albumguid")); put("assetguid", body.getValue("assetguid")); put("uploadjobid", "legacy-job") } }
                path.endsWith("webuploadstatus") -> { if(loseLegacyStatus) { loseLegacyStatus = false; throw IOException("offline") }; JsonArray(listOf(buildJsonObject { put("status", "COMPLETED"); put("uploadjobid", "legacy-job") })) }
                path.endsWith("webgetalbumview") -> buildJsonObject { put("callerId","me"); putJsonArray("albums") { add(buildJsonObject { put("albumguid","legacy"); putJsonObject("attributes") {} }) } }
                path.endsWith("getcomments") -> buildJsonObject { put("comments",legacyComments) }
                path.endsWith("addcomment") -> { if(loseLegacyCommentResponse) throw IOException("lost comment"); buildJsonObject { put("commentposition","1") } }
                path.endsWith("records/query") -> {
                    val query = body["query"]!!.jsonObject
                    val kind = query["recordType"]!!.jsonPrimitive.content
                    val rows = when {
                        kind == "CPLPostByPostTimestamp" -> {
                            val rank = query["filterBy"]!!.jsonArray.first { it.jsonObject["fieldName"]!!.jsonPrimitive.content == "startRank" }.jsonObject["fieldValue"]!!.jsonObject["value"]!!.jsonPrimitive.int
                            (rank until minOf(rank + 25, activityCount)).map(::post)
                        }
                        kind == "CPLAssetAndMasterInPost" && splitPostPreview -> pair(if (body["continuationMarker"] == null) 0 else 1, false)
                        kind.startsWith("CPLTextCommentFor") || kind.startsWith("CPLReactFor") -> emptyList()
                        else -> pair(0, false)
                    }
                    buildJsonObject {
                        put("records", JsonArray(rows))
                        if (kind == "CPLAssetAndMasterInPost" && splitPostPreview && body["continuationMarker"] == null) put("continuationMarker", "next-photo")
                    }
                }
                else -> error("Unexpected route: $path")
            }
            return AppleHttpResponse(200, emptyMap(), result.toString())
        }
        override suspend fun executeRawUpload(url: String, headers: Map<String, String>, contentLength: Long, openBodyOnce: () -> InputStream): AppleHttpResponse {
            rawUploads++; openBodyOnce().use { assertEquals(4, it.readBytes().size) }
            return AppleHttpResponse(200, emptyMap(), """{"singleFile":{"size":4,"fileChecksum":"a","referenceChecksum":"b","wrappingKey":"c","receipt":"d"}}""")
        }
        override suspend fun stream(url: String, output: OutputStream) {
            streams++; if(expire && streams == 1) throw AppleMediaHttpException(403)
            output.write("image".toByteArray())
        }
        override fun restore(snapshot: AppleSessionSnapshot) = Unit
        override fun clear() = Unit
        override fun snapshotCookies() = emptyList<PersistedCookie>()
    }
}
