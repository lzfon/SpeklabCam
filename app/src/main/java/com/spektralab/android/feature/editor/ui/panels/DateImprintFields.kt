package com.spektralab.android.feature.editor.ui.panels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.spektralab.android.R
import com.spektralab.android.feature.editor.EditorViewModel
import com.spektralab.android.ui.theme.TheiaShapes

@Composable
internal fun DateImprintFields(vm: EditorViewModel) {
    val metadata = vm.dateImprintMetadata
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.date_imprint_photo_hint), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (metadata.available) TextButton(onClick = vm::usePhotoDateImprint,
            modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.date_imprint_use_photo))
        }
        OutlinedTextField(
            value = vm.params.dateImprintText,
            onValueChange = { vm.params.dateImprintText = it; vm.scheduleRender() },
            label = { Text(stringResource(R.string.date_imprint_date)) },
            supportingText = { Text(stringResource(if (metadata.dateText.isEmpty())
                R.string.date_imprint_date_missing else R.string.date_imprint_date_found)) },
            singleLine = true, shape = TheiaShapes.control, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = vm.params.dateImprintDataText,
            onValueChange = { vm.params.dateImprintDataText = it; vm.scheduleRender() },
            label = { Text(stringResource(R.string.date_imprint_data)) },
            supportingText = { Text(stringResource(if (metadata.dataText.isEmpty())
                R.string.date_imprint_data_missing else R.string.date_imprint_data_found)) },
            shape = TheiaShapes.control, modifier = Modifier.fillMaxWidth(),
        )
    }
}
