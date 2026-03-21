package com.sheinsez.mdropdx12.remote.data.model

data class ButtonTemplate(
    val label: String,
    val actionType: ButtonActionType,
    val payload: String,
)

data class ButtonTemplateCategory(
    val name: String,
    val templates: List<ButtonTemplate>,
)

/** Predefined button templates matching MDropDX12's ButtonWindow actions. */
object ButtonTemplates {

    val categories: List<ButtonTemplateCategory> = listOf(
        ButtonTemplateCategory("Presets", listOf(
            ButtonTemplate("Next Preset", ButtonActionType.ScriptCommand, "ACTION=NextPreset"),
            ButtonTemplate("Prev Preset", ButtonActionType.ScriptCommand, "ACTION=PrevPreset"),
            ButtonTemplate("Random Mashup", ButtonActionType.ScriptCommand, "ACTION=RandomMashup"),
            ButtonTemplate("Lock Preset", ButtonActionType.ScriptCommand, "LOCK"),
            ButtonTemplate("Hard Cut", ButtonActionType.ScriptCommand, "ACTION=HardCut"),
            ButtonTemplate("Toggle Random", ButtonActionType.ScriptCommand, "ACTION=ToggleRandom"),
            ButtonTemplate("Save Preset", ButtonActionType.ScriptCommand, "ACTION=SavePreset"),
            ButtonTemplate("Quick Save", ButtonActionType.ScriptCommand, "QUICKSAVE"),
            ButtonTemplate("Clear Preset", ButtonActionType.ScriptCommand, "CLEARPRESET"),
            ButtonTemplate("Auto Preset", ButtonActionType.ScriptCommand, "ACTION=AutoPresetChange"),
        )),
        ButtonTemplateCategory("Media", listOf(
            ButtonTemplate("Play / Pause", ButtonActionType.SendKey, "0xB3"),
            ButtonTemplate("Next Track", ButtonActionType.SendKey, "0xB0"),
            ButtonTemplate("Prev Track", ButtonActionType.SendKey, "0xB1"),
            ButtonTemplate("Stop", ButtonActionType.ScriptCommand, "ACTION=MediaStop"),
            ButtonTemplate("Rewind", ButtonActionType.ScriptCommand, "ACTION=MediaRewind"),
            ButtonTemplate("Fast Forward", ButtonActionType.ScriptCommand, "ACTION=MediaFastFwd"),
        )),
        ButtonTemplateCategory("Window", listOf(
            ButtonTemplate("Fullscreen", ButtonActionType.Signal, "SIG_FULLSCREEN"),
            ButtonTemplate("Borderless", ButtonActionType.Signal, "SIG_BORDERLESS"),
            ButtonTemplate("Always On Top", ButtonActionType.ScriptCommand, "ACTION=AlwaysOnTop"),
            ButtonTemplate("Transparency", ButtonActionType.ScriptCommand, "ACTION=TransparencyMode"),
            ButtonTemplate("Black Mode", ButtonActionType.ScriptCommand, "ACTION=BlackMode"),
            ButtonTemplate("FPS Cycle", ButtonActionType.ScriptCommand, "ACTION=FPSCycle"),
            ButtonTemplate("Show FPS", ButtonActionType.ScriptCommand, "ACTION=ShowFPS"),
            ButtonTemplate("Screenshot", ButtonActionType.ScriptCommand, "ACTION=Screenshot"),
        )),
        ButtonTemplateCategory("Display", listOf(
            ButtonTemplate("Mirror", ButtonActionType.Signal, "SIG_MIRROR"),
            ButtonTemplate("Mirror + WM", ButtonActionType.Signal, "SIG_MIRROR_WM"),
            ButtonTemplate("Watermark", ButtonActionType.Signal, "SIG_WATERMARK"),
            ButtonTemplate("Capture", ButtonActionType.Signal, "SIG_CAPTURE"),
            ButtonTemplate("Stretch", ButtonActionType.ScriptCommand, "ACTION=ToggleStretch"),
            ButtonTemplate("Stretch Only", ButtonActionType.ScriptCommand, "ACTION=ToggleStretchOnly"),
        )),
        ButtonTemplateCategory("Opacity", listOf(
            ButtonTemplate("Opacity Up", ButtonActionType.ScriptCommand, "ACTION=OpacityUp"),
            ButtonTemplate("Opacity Down", ButtonActionType.ScriptCommand, "ACTION=OpacityDown"),
            ButtonTemplate("Opacity 25%", ButtonActionType.ScriptCommand, "ACTION=Opacity25"),
            ButtonTemplate("Opacity 50%", ButtonActionType.ScriptCommand, "ACTION=Opacity50"),
            ButtonTemplate("Opacity 75%", ButtonActionType.ScriptCommand, "ACTION=Opacity75"),
            ButtonTemplate("Opacity 100%", ButtonActionType.ScriptCommand, "ACTION=Opacity100"),
        )),
        ButtonTemplateCategory("Visuals", listOf(
            ButtonTemplate("Wave Mode +", ButtonActionType.ScriptCommand, "ACTION=WaveModeNext"),
            ButtonTemplate("Wave Mode -", ButtonActionType.ScriptCommand, "ACTION=WaveModePrev"),
            ButtonTemplate("Wave Alpha +", ButtonActionType.ScriptCommand, "ACTION=WaveAlphaUp"),
            ButtonTemplate("Wave Alpha -", ButtonActionType.ScriptCommand, "ACTION=WaveAlphaDown"),
            ButtonTemplate("Wave Scale +", ButtonActionType.ScriptCommand, "ACTION=WaveScaleUp"),
            ButtonTemplate("Wave Scale -", ButtonActionType.ScriptCommand, "ACTION=WaveScaleDown"),
            ButtonTemplate("Zoom In", ButtonActionType.ScriptCommand, "ACTION=ZoomIn"),
            ButtonTemplate("Zoom Out", ButtonActionType.ScriptCommand, "ACTION=ZoomOut"),
            ButtonTemplate("Warp Amt +", ButtonActionType.ScriptCommand, "ACTION=WarpAmtUp"),
            ButtonTemplate("Warp Amt -", ButtonActionType.ScriptCommand, "ACTION=WarpAmtDown"),
            ButtonTemplate("Rotate Left", ButtonActionType.ScriptCommand, "ACTION=RotateLeft"),
            ButtonTemplate("Rotate Right", ButtonActionType.ScriptCommand, "ACTION=RotateRight"),
            ButtonTemplate("Brightness +", ButtonActionType.ScriptCommand, "ACTION=BrightnessUp"),
            ButtonTemplate("Brightness -", ButtonActionType.ScriptCommand, "ACTION=BrightnessDown"),
            ButtonTemplate("Hue Forward", ButtonActionType.ScriptCommand, "ACTION=HueForward"),
            ButtonTemplate("Hue Backward", ButtonActionType.ScriptCommand, "ACTION=HueBackward"),
            ButtonTemplate("Gamma +", ButtonActionType.ScriptCommand, "ACTION=GammaUp"),
            ButtonTemplate("Gamma -", ButtonActionType.ScriptCommand, "ACTION=GammaDown"),
            ButtonTemplate("Scramble Warp", ButtonActionType.ScriptCommand, "ACTION=ScrambleWarp"),
            ButtonTemplate("Scramble Comp", ButtonActionType.ScriptCommand, "ACTION=ScrambleComp"),
        )),
        ButtonTemplateCategory("Effects", listOf(
            ButtonTemplate("Sprite Mode", ButtonActionType.ScriptCommand, "ACTION=SpriteMode"),
            ButtonTemplate("Clear Sprites", ButtonActionType.ScriptCommand, "CLEARSPRITES"),
            ButtonTemplate("Clear Texts", ButtonActionType.ScriptCommand, "CLEARTEXTS"),
            ButtonTemplate("Inject Effect", ButtonActionType.ScriptCommand, "ACTION=InjectEffectCycle"),
            ButtonTemplate("Hardcut Mode", ButtonActionType.ScriptCommand, "ACTION=HardcutModeCycle"),
            ButtonTemplate("Shader Lock", ButtonActionType.ScriptCommand, "ACTION=ShaderLockCycle"),
            ButtonTemplate("Song Title", ButtonActionType.ScriptCommand, "ACTION=SongTitle"),
        )),
        ButtonTemplateCategory("Quality", listOf(
            ButtonTemplate("Quality Up", ButtonActionType.ScriptCommand, "ACTION=QualityUp"),
            ButtonTemplate("Quality Down", ButtonActionType.ScriptCommand, "ACTION=QualityDown"),
        )),
        ButtonTemplateCategory("Spout", listOf(
            ButtonTemplate("Toggle Spout", ButtonActionType.ScriptCommand, "ACTION=SpoutToggle"),
            ButtonTemplate("Spout Fixed Size", ButtonActionType.ScriptCommand, "ACTION=SpoutFixedSize"),
        )),
        ButtonTemplateCategory("Info / Debug", listOf(
            ButtonTemplate("Sound Info", ButtonActionType.ScriptCommand, "ACTION=DebugInfo"),
            ButtonTemplate("Preset Info", ButtonActionType.ScriptCommand, "ACTION=ShowPresetInfo"),
            ButtonTemplate("Song Info", ButtonActionType.ScriptCommand, "ACTION=OpenSongInfo"),
            ButtonTemplate("Show Rating", ButtonActionType.ScriptCommand, "ACTION=ShowRating"),
            ButtonTemplate("Shader Help", ButtonActionType.ScriptCommand, "ACTION=ShowShaderHelp"),
        )),
        ButtonTemplateCategory("Windows", listOf(
            ButtonTemplate("Settings", ButtonActionType.ScriptCommand, "ACTION=OpenSettings"),
            ButtonTemplate("Displays", ButtonActionType.ScriptCommand, "ACTION=OpenDisplays"),
            ButtonTemplate("Hotkeys", ButtonActionType.ScriptCommand, "ACTION=OpenHotkeys"),
            ButtonTemplate("Board", ButtonActionType.ScriptCommand, "ACTION=OpenBoard"),
            ButtonTemplate("Presets", ButtonActionType.ScriptCommand, "ACTION=OpenPresets"),
            ButtonTemplate("Sprites", ButtonActionType.ScriptCommand, "ACTION=OpenSprites"),
            ButtonTemplate("Messages", ButtonActionType.ScriptCommand, "ACTION=OpenMessages"),
            ButtonTemplate("Shader Import", ButtonActionType.ScriptCommand, "ACTION=OpenShaderImport"),
            ButtonTemplate("VideoFX", ButtonActionType.ScriptCommand, "ACTION=OpenVideoFX"),
            ButtonTemplate("Remote", ButtonActionType.ScriptCommand, "ACTION=OpenRemote"),
            ButtonTemplate("Visual", ButtonActionType.ScriptCommand, "ACTION=OpenVisual"),
            ButtonTemplate("Colors", ButtonActionType.ScriptCommand, "ACTION=OpenColors"),
            ButtonTemplate("Script", ButtonActionType.ScriptCommand, "ACTION=OpenScript"),
        )),
    )
}
