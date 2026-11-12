package com.fish5.utils.ui

import com.fish5.Fish5.mc
import com.fish5.features.impl.render.ClickGUIModule

// mouseHandler.xpos()/ypos() are raw GLFW cursor coords in OS "screen coordinate" space (same units
// as Window#getScreenWidth/getScreenHeight) -- NOT the vanilla-GUI-Scale-adjusted logical pixel space
// that GuiGraphicsExtractor draws in (Window#getGuiScaledWidth/getGuiScaledHeight) and that
// ClickGUIModule.hoverScale() divides by below to get layout coordinates. getScaledXPos/YPos
// apply vanilla's own screen-coords -> gui-scaled-pixels conversion first, matching that space.
inline val mouseX: Float
    get() =
        mc.mouseHandler.getScaledXPos(mc.window).toFloat()

inline val mouseY: Float
    get() =
        mc.mouseHandler.getScaledYPos(mc.window).toFloat()

fun isAreaHovered(x: Float, y: Float, w: Float, h: Float, scaled: Boolean = false): Boolean =
    if (scaled) mouseX / ClickGUIModule.hoverScale() in x..(x + w) && mouseY / ClickGUIModule.hoverScale() in y..(y + h)
    else mouseX in x..(x + w) && mouseY in y..(y + h)

fun isAreaHovered(x: Float, y: Float, w: Float, scaled: Boolean = false): Boolean =
    if (scaled) mouseX / ClickGUIModule.hoverScale() in x..(x + w) && mouseY / ClickGUIModule.hoverScale() >= y
    else mouseX in x..(x + w) && mouseY >= y