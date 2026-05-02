package com.openroute.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.openroute.app.R

@Composable
internal fun OpenRouteSplash(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_openroute_logo_full),
            contentDescription = null,
            modifier = Modifier.size(300.dp),
        )
    }
}

@Composable
internal fun OpenRouteWordmark(
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    val routeColor = MaterialTheme.colorScheme.onBackground

    Text(
        text = buildAnnotatedString {
            withStyle(SpanStyle(color = OpenRouteNavy)) {
                append("Open")
            }
            withStyle(SpanStyle(color = routeColor)) {
                append("Route")
            }
        },
        modifier = modifier,
        style = style,
    )
}
