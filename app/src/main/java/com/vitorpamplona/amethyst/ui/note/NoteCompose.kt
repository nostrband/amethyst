package com.vitorpamplona.amethyst.ui.note

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.google.accompanist.flowlayout.FlowRow
import com.vitorpamplona.amethyst.NotificationCache
import com.vitorpamplona.amethyst.R
import com.vitorpamplona.amethyst.RoboHashCache
import com.vitorpamplona.amethyst.model.Note
import com.vitorpamplona.amethyst.model.User
import com.vitorpamplona.amethyst.service.model.ChannelMessageEvent
import com.vitorpamplona.amethyst.service.model.ReactionEvent
import com.vitorpamplona.amethyst.service.model.ReportEvent
import com.vitorpamplona.amethyst.service.model.RepostEvent
import com.vitorpamplona.amethyst.ui.components.AsyncImageProxy
import com.vitorpamplona.amethyst.ui.components.ObserveDisplayNip05Status
import com.vitorpamplona.amethyst.ui.components.ResizeImage
import com.vitorpamplona.amethyst.ui.components.TranslateableRichTextViewer
import com.vitorpamplona.amethyst.ui.screen.loggedIn.AccountViewModel
import com.vitorpamplona.amethyst.ui.theme.Following
import nostr.postr.events.TextNoteEvent

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NoteCompose(
    baseNote: Note,
    routeForLastRead: String? = null,
    modifier: Modifier = Modifier,
    isBoostedNote: Boolean = false,
    isQuotedNote: Boolean = false,
    parentBackgroundColor: Color? = null,
    accountViewModel: AccountViewModel,
    navController: NavController
) {
    val accountState by accountViewModel.accountLiveData.observeAsState()
    val account = accountState?.account ?: return

    val noteState by baseNote.live().metadata.observeAsState()
    val note = noteState?.note

    val noteReportsState by baseNote.live().reports.observeAsState()
    val noteForReports = noteReportsState?.note ?: return

    var popupExpanded by remember { mutableStateOf(false) }
    var showHiddenNote by remember { mutableStateOf(false) }

    val context = LocalContext.current.applicationContext

    var moreActionsExpanded by remember { mutableStateOf(false) }

    val noteEvent = note?.event

    if (noteEvent == null) {
        BlankNote(modifier.combinedClickable(
            onClick = {  },
            onLongClick = { popupExpanded = true },
        ), isBoostedNote)
    } else if (!account.isAcceptable(noteForReports) && !showHiddenNote) {
        HiddenNote(
            account.getRelevantReports(noteForReports),
            account.userProfile(),
            modifier,
            isBoostedNote,
            navController,
            onClick = { showHiddenNote = true }
        )
    } else {
        var isNew by remember { mutableStateOf<Boolean>(false) }

        LaunchedEffect(key1 = routeForLastRead) {
            routeForLastRead?.let {
                val lastTime = NotificationCache.load(it, context)

                val createdAt = noteEvent.createdAt
                if (createdAt != null) {
                    NotificationCache.markAsRead(it, createdAt, context)
                    isNew = createdAt > lastTime
                }
            }
        }

        var backgroundColor = if (isNew) {
            val newColor = MaterialTheme.colors.primary.copy(0.12f)
            if (parentBackgroundColor != null) {
                newColor.compositeOver(parentBackgroundColor)
            } else {
                newColor.compositeOver(MaterialTheme.colors.background)
            }
          } else {
            parentBackgroundColor ?: MaterialTheme.colors.background
          }

        Column(modifier = modifier
            .combinedClickable(
                onClick = {
                    if (noteEvent !is ChannelMessageEvent) {
                        navController.navigate("Note/${note.idHex}") {
                            launchSingleTop = true
                        }
                    } else {
                        note.channel?.let {
                            navController.navigate("Channel/${it.idHex}")
                        }
                    }
                },
                onLongClick = { popupExpanded = true }
            )
            .background(backgroundColor)
        ) {
            Row(
                modifier = Modifier
                    .padding(
                        start = if (!isBoostedNote) 12.dp else 0.dp,
                        end = if (!isBoostedNote) 12.dp else 0.dp,
                        top = 10.dp)
            ) {

                if (!isBoostedNote && !isQuotedNote) {
                    Column(Modifier.width(55.dp)) {
                    // Draws the boosted picture outside the boosted card.
                        Box(modifier = Modifier
                            .width(55.dp)
                            .padding(0.dp)) {

                            NoteAuthorPicture(note, navController, account.userProfile(), 55.dp)

                            if (noteEvent is RepostEvent) {
                                note.replyTo?.lastOrNull()?.let {
                                    Box(
                                        Modifier
                                            .width(30.dp)
                                            .height(30.dp)
                                            .align(Alignment.BottomEnd)) {
                                        NoteAuthorPicture(it, navController, account.userProfile(), 35.dp,
                                            pictureModifier = Modifier.border(2.dp, MaterialTheme.colors.background, CircleShape)
                                        )
                                    }
                                }
                            }

                            // boosted picture
                            val baseChannel = note.channel
                            if (noteEvent is ChannelMessageEvent && baseChannel != null) {
                                val channelState by baseChannel.live.observeAsState()
                                val channel = channelState?.channel

                                if (channel != null) {
                                    Box(
                                        Modifier
                                            .width(30.dp)
                                            .height(30.dp)
                                            .align(Alignment.BottomEnd)) {
                                        AsyncImageProxy(
                                            model = ResizeImage(channel.profilePicture(), 30.dp),
                                            placeholder = BitmapPainter(RoboHashCache.get(context, channel.idHex)),
                                            fallback = BitmapPainter(RoboHashCache.get(context, channel.idHex)),
                                            error = BitmapPainter(RoboHashCache.get(context, channel.idHex)),
                                            contentDescription = "Group Picture",
                                            modifier = Modifier
                                                .width(30.dp)
                                                .height(30.dp)
                                                .clip(shape = CircleShape)
                                                .background(MaterialTheme.colors.background)
                                                .border(
                                                    2.dp,
                                                    MaterialTheme.colors.background,
                                                    CircleShape
                                                )
                                        )
                                    }
                                }
                            }
                        }

                        if (noteEvent is RepostEvent) {
                            note.replyTo?.lastOrNull()?.let {
                                RelayBadges(it)
                            }
                        } else {
                            RelayBadges(baseNote)
                        }
                    }
                }

                Column(
                    modifier = Modifier.padding(start = if (!isBoostedNote && !isQuotedNote) 10.dp else 0.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isQuotedNote) {
                            NoteAuthorPicture(note, navController, account.userProfile(), 25.dp)
                            Spacer(Modifier.padding(horizontal = 5.dp))
                            NoteUsernameDisplay(note, Modifier.weight(1f))
                        } else {
                            NoteUsernameDisplay(note, Modifier.weight(1f))
                        }


                        if (noteEvent !is RepostEvent) {
                            Text(
                                timeAgo(noteEvent.createdAt),
                                color = MaterialTheme.colors.onSurface.copy(alpha = 0.32f),
                                maxLines = 1
                            )

                            IconButton(
                                modifier = Modifier.then(Modifier.size(24.dp)),
                                onClick = { moreActionsExpanded = true }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    null,
                                    modifier = Modifier.size(15.dp),
                                    tint = MaterialTheme.colors.onSurface.copy(alpha = 0.32f),
                                )

                                NoteDropDownMenu(baseNote, moreActionsExpanded, { moreActionsExpanded = false }, accountViewModel)
                            }
                        } else {
                            Text(
                                "  boosted",
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colors.onSurface.copy(alpha = 0.32f)
                            )
                        }
                    }

                    if (note.author != null)
                        ObserveDisplayNip05Status(note.author!!)

                    if (noteEvent is TextNoteEvent && (note.replyTo != null || note.mentions != null)) {
                        ReplyInformation(note.replyTo, note.mentions, account, navController)
                    } else if (noteEvent is ChannelMessageEvent && (note.replyTo != null || note.mentions != null)) {
                        val sortedMentions = note.mentions?.toSet()?.sortedBy { account.userProfile().isFollowing(it) }

                        note.channel?.let {
                            ReplyInformationChannel(note.replyTo, sortedMentions, it, navController)
                        }
                    }

                    if (noteEvent is ReactionEvent || noteEvent is RepostEvent) {
                        note.replyTo?.lastOrNull()?.let {
                            NoteCompose(
                                it,
                                modifier = Modifier,
                                isBoostedNote = true,
                                parentBackgroundColor = backgroundColor,
                                accountViewModel = accountViewModel,
                                navController = navController
                            )
                        }

                        // Reposts have trash in their contents.
                        if (noteEvent is ReactionEvent) {
                            val refactorReactionText =
                                if (noteEvent.content == "+") "❤" else noteEvent.content ?: " "

                            Text(
                                text = refactorReactionText
                            )
                        }
                    } else if (noteEvent is ReportEvent) {
                        val reportType = noteEvent.reportType.map {
                            when (it) {
                                ReportEvent.ReportType.EXPLICIT -> "Explicit Content"
                                ReportEvent.ReportType.SPAM -> "Spam"
                                ReportEvent.ReportType.IMPERSONATION -> "Impersonation"
                                ReportEvent.ReportType.ILLEGAL -> "Illegal Behavior"
                                else -> "Unknown"
                            }
                        }.joinToString(", ")

                        Text(
                            text = reportType
                        )

                        Divider(
                            modifier = Modifier.padding(top = 10.dp),
                            thickness = 0.25.dp
                        )
                    } else {
                        val eventContent = noteEvent.content
                        val canPreview = note.author == account.userProfile()
                          || (note.author?.let { account.userProfile().isFollowing(it) } ?: true )
                          || !noteForReports.hasAnyReports()

                        if (eventContent != null) {
                            TranslateableRichTextViewer(
                                eventContent,
                                canPreview,
                                Modifier.fillMaxWidth(),
                                noteEvent.tags,
                                backgroundColor,
                                accountViewModel,
                                navController
                            )
                        }

                        ReactionsRow(note, accountViewModel)

                        Divider(
                            modifier = Modifier.padding(top = 10.dp),
                            thickness = 0.25.dp
                        )
                    }

                    NoteDropDownMenu(note, popupExpanded, { popupExpanded = false }, accountViewModel)
                }
            }
        }
    }
}

@Composable
private fun RelayBadges(baseNote: Note) {
    val noteRelaysState by baseNote.live().relays.observeAsState()
    val noteRelays = noteRelaysState?.note?.relays ?: emptySet()

    var expanded by remember { mutableStateOf(false) }

    val relaysToDisplay = if (expanded) noteRelays else noteRelays.take(3)

    val uri = LocalUriHandler.current
    val ctx = LocalContext.current.applicationContext

    FlowRow(Modifier.padding(top = 10.dp, start = 5.dp, end = 4.dp)) {
        relaysToDisplay.forEach {
            val url = it.removePrefix("wss://").removePrefix("ws://")
            Box(
                Modifier
                    .size(15.dp)
                    .padding(1.dp)) {
                AsyncImage(
                    model = "https://${url}/favicon.ico",
                    placeholder = BitmapPainter(RoboHashCache.get(ctx, url)),
                    fallback = BitmapPainter(RoboHashCache.get(ctx, url)),
                    error = BitmapPainter(RoboHashCache.get(ctx, url)),
                    contentDescription = "Relay Icon",
                    colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }),
                    modifier = Modifier
                        .fillMaxSize(1f)
                        .clip(shape = CircleShape)
                        .background(MaterialTheme.colors.background)
                        .clickable(onClick = { uri.openUri("https://" + url) })
                )
            }
        }
    }

    if (noteRelays.size > 3 && !expanded) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(25.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.Top) {
            IconButton(
                modifier = Modifier.then(Modifier.size(24.dp)),
                onClick = { expanded = true }
            ) {
                Icon(
                    imageVector = Icons.Default.ExpandMore,
                    null,
                    modifier = Modifier.size(15.dp),
                    tint = MaterialTheme.colors.onSurface.copy(alpha = 0.32f),
                )
            }
        }
    }
}


@Composable
fun NoteAuthorPicture(
    note: Note,
    navController: NavController,
    userAccount: User,
    size: Dp,
    pictureModifier: Modifier = Modifier
) {
    NoteAuthorPicture(note, userAccount, size, pictureModifier) {
        navController.navigate("User/${it.pubkeyHex}")
    }
}


@Composable
fun NoteAuthorPicture(
    baseNote: Note,
    baseUserAccount: User,
    size: Dp,
    pictureModifier: Modifier = Modifier,
    onClick: ((User) -> Unit)? = null
) {
    val noteState by baseNote.live().metadata.observeAsState()
    val note = noteState?.note ?: return

    val author = note.author

    val ctx = LocalContext.current.applicationContext

    Box(
        Modifier
            .width(size)
            .height(size)) {
        if (author == null) {
            Image(
                painter = BitmapPainter(RoboHashCache.get(ctx, "ohnothisauthorisnotfound")),
                contentDescription = "Unknown Author",
                modifier = pictureModifier
                    .fillMaxSize(1f)
                    .clip(shape = CircleShape)
                    .background(MaterialTheme.colors.background)
            )
        } else {
            UserPicture(author, baseUserAccount, size, pictureModifier, onClick)
        }
    }
}

@Composable
fun UserPicture(
    user: User,
    navController: NavController,
    userAccount: User,
    size: Dp,
    pictureModifier: Modifier = Modifier
) {
    UserPicture(user, userAccount, size, pictureModifier) {
        navController.navigate("User/${it.pubkeyHex}")
    }
}

@Composable
fun UserPicture(
    baseUser: User,
    baseUserAccount: User,
    size: Dp,
    pictureModifier: Modifier = Modifier,
    onClick: ((User) -> Unit)? = null
) {
    val userState by baseUser.live().metadata.observeAsState()
    val user = userState?.user ?: return

    val ctx = LocalContext.current.applicationContext

    Box(
        Modifier
            .width(size)
            .height(size)) {

        AsyncImageProxy(
            model = ResizeImage(user.profilePicture(), size),
            contentDescription = "Profile Image",
            placeholder = BitmapPainter(RoboHashCache.get(ctx, user.pubkeyHex)),
            fallback = BitmapPainter(RoboHashCache.get(ctx, user.pubkeyHex)),
            error = BitmapPainter(RoboHashCache.get(ctx, user.pubkeyHex)),
            modifier = pictureModifier
                .fillMaxSize(1f)
                .clip(shape = CircleShape)
                .background(MaterialTheme.colors.background)
                .run {
                    if (onClick != null)
                        this.clickable(onClick = { onClick(user) } )
                    else
                        this
                }

        )

        val accountState by baseUserAccount.live().follows.observeAsState()
        val accountUser = accountState?.user ?: return

        if (accountUser.isFollowing(user) || user == accountUser) {
            Box(
                Modifier
                    .width(size.div(3.5f))
                    .height(size.div(3.5f))
                    .align(Alignment.TopEnd),
                contentAlignment = Alignment.Center
            ) {
                // Background for the transparent checkmark
                Box(
                    Modifier
                        .clip(CircleShape)
                        .fillMaxSize(0.6f)
                        .align(Alignment.Center)
                        .background(MaterialTheme.colors.background)
                )

                Icon(
                    painter = painterResource(R.drawable.ic_verified),
                    "Following",
                    modifier = Modifier.fillMaxSize(),
                    tint = Following
                )
            }
        }

    }
}

@Composable
fun NoteDropDownMenu(note: Note, popupExpanded: Boolean, onDismiss: () -> Unit, accountViewModel: AccountViewModel) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current.applicationContext

    DropdownMenu(
        expanded = popupExpanded,
        onDismissRequest = onDismiss
    ) {
        DropdownMenuItem(onClick = { clipboardManager.setText(AnnotatedString(accountViewModel.decrypt(note) ?: "")); onDismiss() }) {
            Text("Copy Text")
        }
        DropdownMenuItem(onClick = { clipboardManager.setText(AnnotatedString(note.author?.pubkeyNpub() ?: "")); onDismiss() }) {
            Text("Copy User PubKey")
        }
        DropdownMenuItem(onClick = { clipboardManager.setText(AnnotatedString(note.idNote())); onDismiss() }) {
            Text("Copy Note ID")
        }
        Divider()
        DropdownMenuItem(onClick = { accountViewModel.broadcast(note); onDismiss() }) {
            Text("Broadcast")
        }
        if (note.author == accountViewModel.accountLiveData.value?.account?.userProfile()) {
            Divider()
            DropdownMenuItem(onClick = { accountViewModel.delete(note); onDismiss() }) {
                Text("Request Deletion")
            }
        }
        if (note.author != accountViewModel.accountLiveData.value?.account?.userProfile()) {
            Divider()
            DropdownMenuItem(onClick = {
                note.author?.let {
                    accountViewModel.hide(
                        it,
                        context
                    )
                }; onDismiss()
            }) {
                Text("Block & Hide Author")
            }
            Divider()
            DropdownMenuItem(onClick = {
                accountViewModel.report(note, ReportEvent.ReportType.SPAM);
                note.author?.let { accountViewModel.hide(it, context) }
                onDismiss()
            }) {
                Text("Report Spam / Scam")
            }
            DropdownMenuItem(onClick = {
                accountViewModel.report(note, ReportEvent.ReportType.IMPERSONATION);
                note.author?.let { accountViewModel.hide(it, context) }
                onDismiss()
            }) {
                Text("Report Impersonation")
            }
            DropdownMenuItem(onClick = {
                accountViewModel.report(note, ReportEvent.ReportType.EXPLICIT);
                note.author?.let { accountViewModel.hide(it, context) }
                onDismiss()
            }) {
                Text("Report Explicit Content")
            }
            DropdownMenuItem(onClick = {
                accountViewModel.report(note, ReportEvent.ReportType.ILLEGAL);
                note.author?.let { accountViewModel.hide(it, context) }
                onDismiss()
            }) {
                Text("Report Illegal Behaviour")
            }
        }
    }
}