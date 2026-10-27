package com.heybuddy.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.heybuddy.R
import com.heybuddy.ui.theme.Dimens

/**
 * Confirmation for an action that cannot be undone (deleting a document, deleting everything).
 *
 * Cancel is the default: it is the filled, more prominent button and gets focus first; tapping
 * outside or Back also cancels. The dangerous button is in the error colour and ignores taps made
 * through another app's overlay (tapjacking).
 *
 * @param title Short question, for example "Delete this document?".
 * @param body What will happen, in plain words.
 * @param confirmText The dangerous button's label.
 * @param onCancel Called on Cancel, Back or tapping outside.
 * @param onConfirm Called on the dangerous button.
 * @param confirmEnabled False to grey out the dangerous button (for example until "DELETE" is typed).
 * @param extraContent Optional content under the body (for example the "DELETE" text field).
 */
@Composable
fun DangerConfirmDialog(
    title: String,
    body: String,
    confirmText: String,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    confirmEnabled: Boolean = true,
    extraContent: (@Composable () -> Unit)? = null,
) {
    val cancelFocus = remember { FocusRequester() }
    val reportBlockedTouch = LocalObscuredTouchReporter.current
    AlertDialog(
        onDismissRequest = onCancel,
        // Moves the dialog above the keyboard when it has a text field.
        modifier = Modifier.imePadding(),
        title = { Text(text = title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.SpaceL)) {
                Text(text = body, style = MaterialTheme.typography.bodyMedium)
                extraContent?.invoke()
            }
        },
        dismissButton = {
            TextButton(
                onClick = onConfirm,
                enabled = confirmEnabled,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.rejectObscuredTouches(reportBlockedTouch),
            ) {
                Text(text = confirmText, fontWeight = FontWeight.SemiBold)
            }
        },
        confirmButton = {
            FilledTonalButton(onClick = onCancel, modifier = Modifier.focusRequester(cancelFocus)) {
                Text(text = stringResource(R.string.add_cancel))
            }
        },
        shape = MaterialTheme.shapes.extraLarge,
    )
    LaunchedEffect(Unit) { cancelFocus.requestFocus() }
}
