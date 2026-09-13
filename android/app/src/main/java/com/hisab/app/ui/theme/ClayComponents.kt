package com.hisab.app.ui.theme

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hisab.app.ui.ScriptAwareVisualTransformation

/**
 * The moulded-clay surface every box on screen is built from: a coloured
 * shadow below, a pale top edge, and a soft vertical gradient so the shape
 * reads as raised rather than flat.
 */
fun Modifier.claySurface(
    shape: Shape,
    elevation: androidx.compose.ui.unit.Dp = ClayDimens.RestingElevation,
    fill: Color = ClayColors.Surface,
): Modifier =
    this
        .shadow(
            elevation = elevation,
            shape = shape,
            clip = false,
            ambientColor = ClayColors.Shadow,
            spotColor = ClayColors.Shadow,
        ).background(
            brush =
                Brush.verticalGradient(
                    listOf(
                        ClayColors.Highlight.copy(alpha = 0.85f),
                        fill,
                    ),
                ),
            shape = shape,
        ).border(
            BorderStroke(
                1.dp,
                Brush.verticalGradient(
                    listOf(
                        ClayColors.Highlight,
                        ClayColors.Shadow.copy(alpha = 0.25f),
                    ),
                ),
            ),
            shape = shape,
        )

/** App text: always the font of the current language (D010). */
@Composable
fun ClayText(
    text: String,
    modifier: Modifier = Modifier,
    size: Int = 15,
    weight: FontWeight = FontWeight.Normal,
    color: Color = ClayColors.Ink,
) {
    Text(
        text = text,
        modifier = modifier,
        fontFamily = LocalUiFont.current,
        fontSize = size.sp,
        fontWeight = weight,
        color = color,
        style = LocalTextStyle.current,
    )
}

/** Text the user typed: one font per script inside the string (D010). */
@Composable
fun ClayUserText(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    size: Int = 15,
    weight: FontWeight = FontWeight.Normal,
    color: Color = ClayColors.Ink,
    maxLines: Int = Int.MAX_VALUE,
) {
    Text(
        text = text,
        modifier = modifier,
        fontSize = size.sp,
        fontWeight = weight,
        color = color,
        maxLines = maxLines,
    )
}

@Composable
fun ClayCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val elevation by animateDpAsState(
        if (pressed) ClayDimens.PressedElevation else ClayDimens.RestingElevation,
        label = "cardElevation",
    )
    val shape = RoundedCornerShape(ClayDimens.CardCorner)

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .claySurface(shape, elevation)
                .then(
                    if (onClick == null) {
                        Modifier
                    } else {
                        Modifier.clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = onClick,
                        )
                    },
                ).padding(ClayDimens.CardPadding),
    ) {
        Column { content() }
    }
}

enum class ClayButtonStyle { PRIMARY, SOFT, DANGER }

@Composable
fun ClayButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: ClayButtonStyle = ClayButtonStyle.PRIMARY,
    enabled: Boolean = true,
    leadingIcon: Painter? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    // Pressing pushes the button into the clay: it shrinks slightly and its
    // shadow shortens, instead of just changing colour.
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, label = "buttonScale")
    val elevation by animateDpAsState(
        when {
            !enabled -> 0.dp
            pressed -> ClayDimens.PressedElevation
            else -> ClayDimens.RestingElevation
        },
        label = "buttonElevation",
    )

    val fill =
        when {
            !enabled -> ClayColors.SurfaceSunken
            style == ClayButtonStyle.PRIMARY -> ClayColors.Primary
            style == ClayButtonStyle.DANGER -> ClayColors.Danger
            else -> ClayColors.PrimarySoft
        }
    val contentColor =
        when {
            !enabled -> ClayColors.InkMuted
            style == ClayButtonStyle.SOFT -> ClayColors.Primary
            else -> ClayColors.OnPrimary
        }
    val shape = RoundedCornerShape(ClayDimens.ButtonCorner)

    Row(
        modifier =
            modifier
                .scale(scale)
                .heightIn(min = 54.dp)
                .shadow(elevation, shape, clip = false, ambientColor = ClayColors.Shadow, spotColor = ClayColors.Shadow)
                .background(
                    brush =
                        Brush.verticalGradient(
                            listOf(fill.lighten(), fill),
                        ),
                    shape = shape,
                ).clickable(
                    enabled = enabled,
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                ).padding(horizontal = 24.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            Icon(
                painter = leadingIcon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(20.dp).padding(end = 0.dp),
            )
        }
        ClayText(
            text = text,
            size = 16,
            weight = FontWeight.Bold,
            color = contentColor,
            modifier = Modifier.padding(start = if (leadingIcon != null) 10.dp else 0.dp),
        )
    }
}

/** A pill that can be on or off — used for units and for the "show inactive" switch. */
@Composable
fun ClayChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier =
            modifier
                .shadow(
                    if (selected) 2.dp else 6.dp,
                    shape,
                    clip = false,
                    ambientColor = ClayColors.Shadow,
                    spotColor = ClayColors.Shadow,
                ).background(
                    brush =
                        Brush.verticalGradient(
                            if (selected) {
                                listOf(ClayColors.Primary.lighten(), ClayColors.Primary)
                            } else {
                                listOf(ClayColors.Highlight, ClayColors.SurfaceSunken)
                            },
                        ),
                    shape = shape,
                ).clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        ClayText(
            text = label,
            size = 14,
            weight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) ClayColors.OnPrimary else ClayColors.InkMuted,
        )
    }
}

/**
 * A pressed-in (sunken) field, the opposite of the raised card, so an input
 * looks like somewhere to put something.
 */
@Composable
fun ClayTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    errorText: String? = null,
    leadingIcon: Painter? = null,
    trailingContent: @Composable (() -> Unit)? = null,
    userTypedText: Boolean = true,
    /** Names the input for UI tests, which otherwise cannot tell four identical-looking fields apart. */
    fieldTag: String? = null,
) {
    val shape = RoundedCornerShape(ClayDimens.FieldCorner)
    Column(modifier = modifier.fillMaxWidth()) {
        if (label != null) {
            ClayText(
                text = label,
                size = 13,
                weight = FontWeight.Bold,
                color = ClayColors.InkMuted,
                modifier = Modifier.padding(start = 6.dp, bottom = 6.dp),
            )
        }
        TextField(
            value = value,
            onValueChange = onValueChange,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .then(if (fieldTag == null) Modifier else Modifier.testTag(fieldTag))
                    .background(ClayColors.SurfaceSunken, shape)
                    .border(
                        BorderStroke(
                            1.dp,
                            if (errorText != null) ClayColors.Danger else ClayColors.Shadow.copy(alpha = 0.35f),
                        ),
                        shape,
                    ),
            singleLine = singleLine,
            placeholder =
                placeholder?.let {
                    {
                        ClayText(text = it, size = 15, color = ClayColors.InkMuted)
                    }
                },
            leadingIcon =
                leadingIcon?.let {
                    {
                        Icon(painter = it, contentDescription = null, tint = ClayColors.InkMuted)
                    }
                },
            trailingIcon = trailingContent,
            isError = errorText != null,
            // Numbers and names alike keep the per-script font rule (D010).
            visualTransformation =
                if (userTypedText) ScriptAwareVisualTransformation() else VisualTransformation.None,
            textStyle = LocalTextStyle.current.copy(fontSize = 16.sp, color = ClayColors.Ink),
            keyboardOptions =
                androidx.compose.foundation.text
                    .KeyboardOptions(keyboardType = keyboardType),
            shape = shape,
            colors =
                TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    errorContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    errorIndicatorColor = Color.Transparent,
                    cursorColor = ClayColors.Primary,
                ),
        )
        if (errorText != null) {
            ClayText(
                text = errorText,
                size = 13,
                color = ClayColors.Danger,
                modifier = Modifier.padding(start = 6.dp, top = 6.dp),
            )
        }
    }
}

/** A lighter version of a colour, for the top of a moulded gradient. */
private fun Color.lighten(amount: Float = 0.16f): Color =
    Color(
        red = red + (1f - red) * amount,
        green = green + (1f - green) * amount,
        blue = blue + (1f - blue) * amount,
        alpha = alpha,
    )
