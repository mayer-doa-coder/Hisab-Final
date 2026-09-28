package com.hisab.app.ui.sync

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.hisab.app.R
import com.hisab.app.ui.product.ScreenHeader
import com.hisab.app.ui.theme.ClayButton
import com.hisab.app.ui.theme.ClayCard
import com.hisab.app.ui.theme.ClayColors
import com.hisab.app.ui.theme.ClayDimens
import com.hisab.app.ui.theme.ClayText
import com.hisab.app.ui.theme.ClayTextField

/**
 * Sending this shop's changes to the server and taking back what it has
 * (Steps 32–33). Syncing is something the user asks for here; doing it in the
 * background on a schedule is M4.
 */
@Composable
fun SyncScreen(
    viewModel: SyncViewModel,
    onBack: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(ClayColors.Background)
                .safeDrawingPadding()
                .padding(horizontal = ClayDimens.ScreenPadding),
    ) {
        ScreenHeader(title = stringResource(R.string.sync_title), onBack = onBack)

        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ClayTextField(
                value = viewModel.serverUrl,
                onValueChange = viewModel::onServerUrlChange,
                fieldTag = "field_server_url",
                label = stringResource(R.string.sync_server_label),
                keyboardType = KeyboardType.Uri,
                userTypedText = false,
            )
            ClayTextField(
                value = viewModel.email,
                onValueChange = viewModel::onEmailChange,
                fieldTag = "field_email",
                label = stringResource(R.string.sync_email_label),
                keyboardType = KeyboardType.Email,
                userTypedText = false,
            )
            ClayTextField(
                value = viewModel.password,
                onValueChange = viewModel::onPasswordChange,
                fieldTag = "field_password",
                label = stringResource(R.string.sync_password_label),
                placeholder = stringResource(R.string.sync_password_hint),
                keyboardType = KeyboardType.Password,
                password = true,
                userTypedText = false,
            )

            ClayCard {
                ClayText(text = statusText(viewModel.status), size = 15)
            }
        }

        ClayButton(
            text = stringResource(R.string.action_sync_now),
            onClick = viewModel::syncNow,
            enabled = !viewModel.running,
            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
        )
    }
}

/**
 * One plain sentence per status code (Step 66). The code decides what is
 * said; this only looks up the words for the language on screen, so the two
 * languages cannot drift apart into meaning different things (D011).
 */
@Composable
private fun statusText(status: SyncStatus): String =
    when (status.code) {
        SyncStatusCode.SYNC_RUNNING -> stringResource(R.string.sync_running)
        SyncStatusCode.SYNC_NOT_SIGNED_IN -> stringResource(R.string.sync_not_signed_in)
        SyncStatusCode.SYNC_CONFLICT -> stringResource(R.string.sync_status_conflict, status.refused)
        SyncStatusCode.SYNC_UNREACHABLE -> stringResource(R.string.sync_status_unreachable)
        SyncStatusCode.SYNC_PENDING -> stringResource(R.string.sync_status_pending, status.waitingToSend)
        SyncStatusCode.SYNC_SYNCED -> stringResource(R.string.sync_status_synced)
        SyncStatusCode.SYNC_NEVER -> stringResource(R.string.sync_never)
    }
