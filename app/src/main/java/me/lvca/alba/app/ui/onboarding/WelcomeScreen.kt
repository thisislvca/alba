package me.lvca.alba.app.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import me.lvca.alba.app.R
import me.lvca.alba.app.ui.designsystem.AlbaCard
import me.lvca.alba.app.ui.designsystem.AlbaFeatureRow
import me.lvca.alba.app.ui.designsystem.AlbaIcons
import me.lvca.alba.app.ui.designsystem.theme.AlbaSpacing

@Composable
internal fun WelcomeScreen(onConnect: () -> Unit, onDemo: () -> Unit) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    val pageScroll = rememberScrollState()
    LaunchedEffect(page) { pageScroll.scrollTo(0) }
    BackHandler(enabled = page > 0) { page-- }

    Surface(Modifier.fillMaxSize().testTag("welcome"), color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            // In short windows let the entire page scroll instead of squeezing the explanation
            // between a fixed header and buttons. Larger portrait windows keep actions in reach.
            val compact = maxHeight < 600.dp
            Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)).widthIn(max = 560.dp).fillMaxSize()
                .then(if (compact) Modifier.verticalScroll(pageScroll) else Modifier).navigationBarsPadding().padding(horizontal = AlbaSpacing.xxl),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (page > 0) IconButton(onClick = { page-- }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.welcome_back))
                    }
                    Text(stringResource(R.string.brand_name), style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f))
                    Text(stringResource(R.string.welcome_progress, page + 1, 2),
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                AnimatedContent(page, modifier = Modifier.then(if (!compact) Modifier.weight(1f) else Modifier).fillMaxWidth(),
                    transitionSpec = { fadeIn(tween(180, 80)).togetherWith(fadeOut(tween(100))) }, label = "welcome-page") { current ->
                    Column(Modifier.then(if (compact) Modifier.fillMaxWidth() else Modifier.fillMaxSize().verticalScroll(rememberScrollState()))
                        .padding(top = AlbaSpacing.xxl, bottom = AlbaSpacing.xl)) {
                        Surface(shape = MaterialTheme.shapes.extraLarge,
                            color = if (current == 0) Color.White else MaterialTheme.colorScheme.primaryContainer,
                            shadowElevation = if (current == 0) 1.dp else 0.dp,
                            modifier = Modifier.size(100.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                if (current == 0) Image(painterResource(R.drawable.alba_logo_mark), null,
                                    modifier = Modifier.size(100.dp))
                                else Icon(Icons.Outlined.Lock, null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                        Spacer(Modifier.height(AlbaSpacing.xxl))
                        Text(stringResource(if (current == 0) R.string.welcome_title else R.string.welcome_choices_title),
                            style = MaterialTheme.typography.headlineLarge,
                            modifier = Modifier.semantics { heading() }.testTag("welcome-title"))
                        Spacer(Modifier.height(AlbaSpacing.md))
                        Text(stringResource(if (current == 0) R.string.welcome_intro else R.string.welcome_choices_intro),
                            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(28.dp))
                        if (current == 0) {
                            WelcomeFeature(AlbaIcons.PhotoLibrary, R.string.welcome_library_title, R.string.welcome_library_body)
                            WelcomeFeature(AlbaIcons.DownloadForOffline, R.string.welcome_offline_title, R.string.welcome_offline_body)
                            WelcomeFeature(AlbaIcons.CloudQueue, R.string.welcome_backup_title, R.string.welcome_backup_body)
                        } else {
                            WelcomeFeature(Icons.Outlined.Lock, R.string.welcome_privacy_title, R.string.welcome_privacy_body)
                            WelcomeFeature(AlbaIcons.PhoneAndroid, R.string.welcome_phone_title, R.string.welcome_phone_body)
                            WelcomeFeature(AlbaIcons.CloudQueue, R.string.welcome_optional_title, R.string.welcome_optional_body)
                        }
                    }
                }

                Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(top = AlbaSpacing.md, bottom = AlbaSpacing.md),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(AlbaSpacing.sm)) {
                    Button(onClick = { if (page == 0) page = 1 else onConnect() }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        Text(stringResource(if (page == 0) R.string.welcome_continue else R.string.welcome_connect))
                    }
                    TextButton(onClick = onDemo, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.welcome_demo))
                    }
                    Text(stringResource(R.string.welcome_demo_hint), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun WelcomeFeature(icon: ImageVector, title: Int, body: Int) {
    AlbaCard(Modifier.padding(bottom = 10.dp)) {
        AlbaFeatureRow(icon, stringResource(title), stringResource(body))
    }
}
