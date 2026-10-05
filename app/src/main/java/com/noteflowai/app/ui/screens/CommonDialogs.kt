package com.noteflowai.app.ui.screens

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.noteflowai.app.R

@Composable
fun ConfirmDeleteDialog(
    title: String = stringResource(R.string.dialog_confirm_delete_title),
    message: String = stringResource(R.string.dialog_confirm_delete_message),
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        // 16dp: the theme default (28dp) clips the dialog's 24dp content padding.
        shape = RoundedCornerShape(16.dp),
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.dialog_confirm_delete_button), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dialog_cancel_button))
            }
        }
    )
}
