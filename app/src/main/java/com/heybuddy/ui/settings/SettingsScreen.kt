package com.heybuddy.ui.settings

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import com.heybuddy.R
import com.heybuddy.settings.AutoLockChoice
import com.heybuddy.settings.ThemeChoice
import com.heybuddy.ui.components.BannerKind
import com.heybuddy.ui.components.DangerConfirmDialog
import com.heybuddy.ui.components.MessageBanner
import com.heybuddy.ui.components.PrimaryButton
import com.heybuddy.ui.components.ScreenScaffold
import com.heybuddy.ui.theme.Dimens

/**
 * Everything the Settings screen can ask for.
 *
 * @property onBack Back arrow tapped.
 * @property onThemeChosen A theme was picked.
 * @property onAutoLockChosen An auto-lock time was picked.
 * @property onLicenceClick A licence was tapped.
 * @property onDeleteAllClick "Delete everything" tapped.
 * @property onDeleteTextChanged The "Type DELETE" field changed.
 * @property onDeleteAllConfirm The red button in the dialog was tapped.
 * @property onDeleteAllDismiss Cancel (or Back) in the dialog.
 */
class SettingsActions(
    val onBack: () -> Unit,
    val onThemeChosen: (ThemeChoice) -> Unit,
    val onAutoLockChosen: (AutoLockChoice) -> Unit,
    val onLicenceClick: (LicenceEntry) -> Unit,
    val onDeleteAllClick: () -> Unit,
    val onDeleteTextChanged: (String) -> Unit,
    val onDeleteAllConfirm: () -> Unit,
    val onDeleteAllDismiss: () -> Unit,
)

/**
 * Settings (Task 1.10): auto-lock time, theme, privacy, licences, version, and "Delete everything".
 *
 * @param theme The chosen theme.
 * @param autoLock The chosen auto-lock time.
 * @param uiState Dialog state and the "nothing was deleted" notice.
 * @param versionText For example "Version 0.1.0 (1)".
 * @param actions What happens on each tap.
 */
@Composable
fun SettingsScreen(
    theme: ThemeChoice,
    autoLock: AutoLockChoice,
    uiState: SettingsUiState,
    versionText: String,
    actions: SettingsActions,
) {
    ScreenScaffold(topBar = { SettingsTopBar(R.string.settings_title, actions.onBack) }) {
        if (uiState.notConfirmedNotice) {
            MessageBanner(text = stringResource(R.string.delete_all_not_confirmed), kind = BannerKind.INFO)
        }
        AutoLockSection(autoLock, actions.onAutoLockChosen)
        ThemeSection(theme, actions.onThemeChosen)
        PrivacySection()
        LicencesSection(actions.onLicenceClick)
        Text(
            text = versionText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(vertical = Dimens.SpaceS),
        )
        DangerSection(actions.onDeleteAllClick)
    }
    val dialog = uiState.deleteDialog
    if (dialog != null) {
        DeleteAllDialog(dialog, actions)
    }
}

/**
 * A top bar with a back arrow, used by Settings and the licence text.
 *
 * @param title The bar title.
 * @param onBack Back arrow tapped.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsTopBar(@StringRes title: Int, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(text = stringResource(title), style = MaterialTheme.typography.titleLarge) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(painter = painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back))
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}

@Composable
private fun AutoLockSection(selected: AutoLockChoice, onChosen: (AutoLockChoice) -> Unit) {
    SettingsCard(icon = R.drawable.ic_timer, title = R.string.settings_auto_lock_title) {
        Text(
            text = stringResource(R.string.settings_auto_lock_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(modifier = Modifier.selectableGroup()) {
            for (choice in AutoLockChoice.entries) {
                ChoiceRow(label = stringResource(autoLockLabel(choice)), selected = choice == selected, onClick = { onChosen(choice) })
            }
        }
    }
}

@Composable
private fun ThemeSection(selected: ThemeChoice, onChosen: (ThemeChoice) -> Unit) {
    SettingsCard(icon = R.drawable.ic_palette, title = R.string.settings_theme_title) {
        Column(modifier = Modifier.selectableGroup()) {
            for (choice in ThemeChoice.entries) {
                ChoiceRow(label = stringResource(themeLabel(choice)), selected = choice == selected, onClick = { onChosen(choice) })
            }
        }
    }
}

@Composable
private fun PrivacySection() {
    SettingsCard(icon = R.drawable.ic_privacy_tip, title = R.string.settings_privacy_title) {
        PrivacyLine(R.string.settings_privacy_no_internet)
        PrivacyLine(R.string.settings_privacy_no_backup)
        PrivacyLine(R.string.settings_privacy_on_phone)
        // Owner request: the same honest warning as on the onboarding card.
        MessageBanner(text = stringResource(R.string.no_backup_warning), kind = BannerKind.INFO)
    }
}

@Composable
private fun PrivacyLine(@StringRes text: Int) {
    Text(text = stringResource(text), style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun LicencesSection(onLicenceClick: (LicenceEntry) -> Unit) {
    SettingsCard(icon = R.drawable.ic_article, title = R.string.settings_licences_title) {
        for (entry in LicenceEntry.entries) {
            LicenceRow(entry, onClick = { onLicenceClick(entry) })
        }
    }
}

@Composable
private fun LicenceRow(entry: LicenceEntry, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.MinTouchTarget)
            .selectable(selected = false, onClick = onClick, role = Role.Button)
            .padding(vertical = Dimens.SpaceS),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceXs),
    ) {
        Text(text = stringResource(entry.libraryName), style = MaterialTheme.typography.bodyLarge)
        Text(
            text = stringResource(entry.licenceName),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun DangerSection(onDeleteAllClick: () -> Unit) {
    SettingsCard(icon = R.drawable.ic_delete, title = R.string.settings_danger_title, danger = true) {
        Text(
            text = stringResource(R.string.settings_danger_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PrimaryButton(
            text = stringResource(R.string.settings_delete_everything),
            onClick = onDeleteAllClick,
            icon = R.drawable.ic_delete,
            sensitive = true,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
        )
    }
}

/**
 * "Delete everything?": the red button stays greyed out until DELETE is typed exactly; then the
 * app asks "Confirm it's you" before anything is deleted.
 */
@Composable
private fun DeleteAllDialog(dialog: DeleteAllDialogState, actions: SettingsActions) {
    DangerConfirmDialog(
        title = stringResource(R.string.delete_all_title),
        body = stringResource(R.string.delete_all_body),
        confirmText = stringResource(R.string.delete_all_confirm),
        onCancel = actions.onDeleteAllDismiss,
        onConfirm = actions.onDeleteAllConfirm,
        confirmEnabled = dialog.canConfirm,
        extraContent = {
            OutlinedTextField(
                value = dialog.typed,
                onValueChange = actions.onDeleteTextChanged,
                label = { Text(text = stringResource(R.string.delete_all_field_label)) },
                singleLine = true,
                enabled = !dialog.running,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            )
        },
    )
}

/** A bordered card with an icon and a title, matching the document cards (spec 5.14). */
@Composable
private fun SettingsCard(
    @DrawableRes icon: Int,
    @StringRes title: Int,
    danger: Boolean = false,
    content: @Composable () -> Unit,
) {
    val accent = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(Dimens.BorderWidth, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(Dimens.SpaceL),
            verticalArrangement = Arrangement.spacedBy(Dimens.SpaceM),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceM)) {
                Icon(painter = painterResource(icon), contentDescription = null, tint = accent, modifier = Modifier.size(Dimens.BadgeIconSize))
                Text(text = stringResource(title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            }
            content()
        }
    }
}

/** One radio-button row; the whole row is the 48 dp touch target, TalkBack reads it as one choice. */
@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.MinTouchTarget)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceS),
    ) {
        // onClick = null: the row handles the tap, so TalkBack does not announce two controls.
        RadioButton(selected = selected, onClick = null)
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
    }
}

@StringRes
private fun autoLockLabel(choice: AutoLockChoice): Int {
    return when (choice) {
        AutoLockChoice.ONE_MINUTE -> R.string.settings_auto_lock_one
        AutoLockChoice.TWO_MINUTES -> R.string.settings_auto_lock_two
        AutoLockChoice.FIVE_MINUTES -> R.string.settings_auto_lock_five
    }
}

@StringRes
private fun themeLabel(choice: ThemeChoice): Int {
    return when (choice) {
        ThemeChoice.FOLLOW_PHONE -> R.string.settings_theme_follow
        ThemeChoice.LIGHT -> R.string.settings_theme_light
        ThemeChoice.DARK -> R.string.settings_theme_dark
    }
}
