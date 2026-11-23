package io.nekohasekai.sagernet.simple

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/*
 * D-pad and keyboard use of the simple UI (simple-ui "Адаптивная навигация и раскладка", Android TV): a visible ring
 * around whatever has the focus, and the focus moved to the main control of a screen as it opens.
 *
 * Touch never shows the ring: Compose gives the focus to clickable elements only in keyboard input mode.
 */

/** The colour of the focus ring (SimpleTheme: the primary colour). */
val LocalFocusRingColor = staticCompositionLocalOf { Color.Unspecified }

/** Frames [rememberFocusWhenShown] waits at most for its element to be laid out. */
private const val FOCUS_TRIES = 5

/** The ring's width. */
private val RING_WIDTH = 3.dp

/** The shape of a ring drawn on the edge of a list row (inside its bounds). */
private val ROW_RING = RoundedCornerShape(12.dp)

/** Shapes of the Material components the ring goes around. */
object FocusShapes {
    /** Buttons, text buttons, icon buttons: fully rounded. */
    val Pill: Shape = CircleShape

    /** Cards (Material 3 medium shape). */
    val Card: Shape = RoundedCornerShape(12.dp)

    /** Navigation bar and rail items. */
    val NavItem: Shape = RoundedCornerShape(16.dp)
}

/**
 * The ring of a focused element: [outset] > 0 draws it that far outside the bounds (around a filled button or card,
 * where a ring of the brand colour inside would vanish on the fill); a negative [outset] of the ring's width draws it
 * just inside (list rows that span the whole width of a list, which clips them).
 */
private fun ContentDrawScope.drawFocusRing(color: Color, shape: Shape, outset: Dp) {
    val width = RING_WIDTH.toPx()
    // The stroke is centred on the outline: grow the outline by half the width beyond the wanted inner edge.
    val grow = outset.toPx() + width / 2
    val outline = shape.createOutline(Size(size.width + 2 * grow, size.height + 2 * grow), layoutDirection, this)
    translate(-grow, -grow) { drawOutline(outline, color, style = Stroke(width)) }
}

private fun CompositionLocalConsumerModifierNode.ringColor(): Color =
    currentValueOf(LocalFocusRingColor).takeOrElse { currentValueOf(LocalContentColor) }

/**
 * Whether the window has the input focus: a dialog or sheet above keeps the focus of the element below it, but its
 * ring would show through the scrim. Read while drawing, so the ring comes back when the dialog closes.
 */
private fun CompositionLocalConsumerModifierNode.windowFocused(): Boolean = currentValueOf(LocalWindowInfo).isWindowFocused

private inline fun Color.takeOrElse(block: () -> Color): Color = if (this != Color.Unspecified) this else block()

/**
 * The indication of rows made clickable, selectable or toggleable by a modifier (settings, server list, theme
 * choice): Material's ripple, plus the ring inside the row while it has the focus. SimpleTheme provides it as
 * LocalIndication; Material components with their own ripple (buttons, cards) take [focusRing] instead.
 */
object FocusRingIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        FocusRingIndicationNode(interactionSource, ripple().create(interactionSource))

    override fun equals(other: Any?): Boolean = other === this
    override fun hashCode(): Int = javaClass.hashCode()
}

private class FocusRingIndicationNode(
    private val interactionSource: InteractionSource,
    ripple: DelegatableNode,
) : DelegatingNode(), DrawModifierNode, CompositionLocalConsumerModifierNode {

    init {
        delegate(ripple)
    }

    private var focused = false

    override fun onAttach() {
        coroutineScope.launch {
            val active = mutableSetOf<FocusInteraction.Focus>()
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is FocusInteraction.Focus -> active += interaction
                    is FocusInteraction.Unfocus -> active -= interaction.focus
                    else -> return@collect
                }
                val now = active.isNotEmpty()
                if (now != focused) {
                    focused = now
                    invalidateDraw()
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        if (focused && windowFocused()) drawFocusRing(ringColor(), ROW_RING, -RING_WIDTH)
    }
}

/**
 * The focus ring around a Material component that draws its own ripple (Button, Card, IconButton, Surface): put it
 * on the component's modifier. [outset]: how far outside the bounds the ring runs (negative: inside).
 */
fun Modifier.focusRing(shape: Shape, outset: Dp = 3.dp): Modifier = this then FocusRingElement(shape, outset)

private data class FocusRingElement(val shape: Shape, val outset: Dp) : ModifierNodeElement<FocusRingNode>() {
    override fun create() = FocusRingNode(shape, outset)

    override fun update(node: FocusRingNode) {
        node.shape = shape
        node.outset = outset
        node.invalidateDraw()
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "focusRing"
        properties["shape"] = shape
        properties["outset"] = outset
    }
}

private class FocusRingNode(var shape: Shape, var outset: Dp) :
    Modifier.Node(), FocusEventModifierNode, DrawModifierNode, CompositionLocalConsumerModifierNode {

    private var focused = false

    override fun onFocusEvent(focusState: FocusState) {
        if (focusState.isFocused != focused) {
            focused = focusState.isFocused
            invalidateDraw()
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        if (focused && windowFocused()) drawFocusRing(ringColor(), shape, outset)
    }
}

/**
 * A control that is unavailable for a while (busy) without losing a remote's focus: Compose takes the focus from a
 * disabled clickable, and it then drops to the navigation. Such a control stays enabled for the focus, ignores presses
 * itself while [unavailable] and tells accessibility services it is disabled.
 */
fun Modifier.unavailable(unavailable: Boolean): Modifier = if (unavailable) semantics { disabled() } else this

/**
 * A text field a remote can leave: the field keeps the arrow keys for its cursor, so on a D-pad nothing below it
 * (the dialog's buttons) could take the focus. Up and down move the focus on when there is somewhere to go.
 */
@Composable
fun Modifier.leaveWithDpad(): Modifier {
    val focusManager = LocalFocusManager.current
    return onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (event.key) {
            Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Down)
            Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Up)
            else -> false
        }
    }
}

/**
 * Moves the focus here when this element appears and each time [key] changes, in keyboard input mode only (a D-pad,
 * Android TV), so a remote starts on the main control of the screen just opened instead of nowhere; nothing on touch.
 * Not for an item of a lazy list, which appears again whenever it scrolls back into view: there the list asks with
 * [rememberFocusWhenShown] and the item takes its requester.
 */
@Composable
fun Modifier.focusWhenShown(key: Any? = Unit, enabled: Boolean = true): Modifier =
    focusRequester(rememberFocusWhenShown(key, enabled))

/**
 * The requester [focusWhenShown] asks when the caller appears and each time [key] changes (keyboard input mode).
 * [bringIntoComposition]: for an item of a lazy list, scrolls the list to it when it is not composed (a recreated
 * activity, after a theme change, restores the list scrolled away from it); the focus is asked again then.
 */
@Composable
fun rememberFocusWhenShown(
    key: Any? = Unit,
    enabled: Boolean = true,
    bringIntoComposition: (suspend () -> Unit)? = null,
): FocusRequester {
    val requester = remember { FocusRequester() }
    val inputMode = LocalInputModeManager.current
    LaunchedEffect(key, enabled) {
        if (enabled && inputMode.inputMode == InputMode.Keyboard) {
            // After a layout has placed the element: the first frame after a recreated activity (a theme change)
            // comes before its first layout, and an item of a lazy list exists only after the list's measure.
            suspend fun tryFocus(): Boolean {
                repeat(FOCUS_TRIES) {
                    withFrameNanos { }
                    if (runCatching { requester.requestFocus() }.isSuccess) return true
                }
                return false
            }
            if (!tryFocus() && bringIntoComposition != null) {
                bringIntoComposition()
                tryFocus()
            }
        }
    }
    return requester
}
