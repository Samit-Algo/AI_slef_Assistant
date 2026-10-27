package com.heybuddy.ui.recovery

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.heybuddy.R
import com.heybuddy.ui.components.DangerConfirmDialog
import com.heybuddy.ui.components.HeroIcon
import com.heybuddy.ui.components.LoadingState
import com.heybuddy.ui.components.PrimaryButton
import com.heybuddy.ui.components.ScreenScaffold
import com.heybuddy.ui.theme.Dimens

/**
 * Shown ONLY when the master key is certainly gone for good (rule 14). Explains simply that the
 * vault cannot be recovered and offers a fresh start, behind a clear confirmation.
 *
 * @param deleting True while the old vault is being deleted.
 * @param onDeleteConfirmed Called when the user confirmed "Delete everything".
 */
@Composable
fun RecoveryScreen(deleting: Boolean, onDeleteConfirmed: () -> Unit) {
    var confirmOpen by remember { mutableStateOf(false) }
    ScreenScaffold(centerContent = true) {
        HeroIcon(
            icon = R.drawable.ic_shield,
            tint = MaterialTheme.colorScheme.error,
            container = MaterialTheme.colorScheme.errorContainer,
        )
        Spacer(modifier = Modifier.height(Dimens.SpaceS))
        Text(
            text = stringResource(R.string.recovery_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.recovery_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(Dimens.SpaceL))
        if (deleting) {
            LoadingState(text = stringResource(R.string.recovery_deleting))
        } else {
            PrimaryButton(
                text = stringResource(R.string.recovery_delete_button),
                onClick = { confirmOpen = true },
                sensitive = true,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            )
        }
    }
    if (confirmOpen && !deleting) {
        DangerConfirmDialog(
            title = stringResource(R.string.recovery_confirm_title),
            body = stringResource(R.string.recovery_confirm_body),
            confirmText = stringResource(R.string.recovery_confirm_delete),
            onCancel = { confirmOpen = false },
            onConfirm = {
                confirmOpen = false
                onDeleteConfirmed()
            },
        )
    }
}
