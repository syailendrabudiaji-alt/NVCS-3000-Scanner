package com.nvcs3000.scanner

/** Module metadata extracted from NVCS3000_Low_Ambience_Mode.py.
 * Metadata only: Android behavior must be connected in MainActivity.
 */
data class NvcsModuleInfo(
    val name: String,
    val description: String,
    val energyCostPerSecond: Double,
    val energyLabel: String,
)

object NvcsModuleCatalog {
    val all: List<NvcsModuleInfo> = listOf(
        NvcsModuleInfo("Nightvision", "Boosts visibility with a green night-vision-style filter.", 0.035, "Low-medium"),
        NvcsModuleInfo("Human Detector", "Finds possible faces and full-body shapes. Boxes are visual estimates, not identity recognition.", 0.09, "High"),
        NvcsModuleInfo("Threat Detector", "Flags movement in the configured restricted zone and selected objects. It cannot infer intent.", 0.2, "Very high"),
        NvcsModuleInfo("Thermal Vision", "Applies a thermal-style false-color filter; it does not measure real temperature.", 0.065, "Medium-high"),
        NvcsModuleInfo("Motion Tracker", "Draws boxes and temporary IDs around moving image regions.", 0.055, "Medium"),
        NvcsModuleInfo("Digital Zoom", "Magnifies the center of the camera image using digital cropping.", 0.02, "Very low"),
        NvcsModuleInfo("Radar Scanner", "Plots detected motion regions on a decorative radar display.", 0.025, "Low"),
        NvcsModuleInfo("Edge Detection", "Highlights strong edges and outlines in the camera image.", 0.05, "Medium"),
        NvcsModuleInfo("Event Recorder", "Writes motion and alert events to logs/scanner_events.csv.", 0.015, "Very low"),
        NvcsModuleInfo("Target Lock", "Highlights the detection closest to the center of the image.", 0.045, "Medium"),
        NvcsModuleInfo("Range Finder", "Estimates near/medium/far from apparent image size, not calibrated distance.", 0.025, "Low"),
        NvcsModuleInfo("Signal Scanner", "Sweeps a line across the image and displays a motion-based signal estimate.", 0.02, "Low"),
        NvcsModuleInfo("Snapshot / Freeze Frame", "Space freezes/resumes the image; S saves a PNG snapshot.", 0.01, "Very low"),
        NvcsModuleInfo("Image Stabilizer", "Attempts to reduce small camera movement using optical flow.", 0.085, "High"),
        NvcsModuleInfo("System Diagnostics", "Displays FPS, processing time, and camera resolution.", 0.01, "Very low"),
        NvcsModuleInfo("Echo Scan", "Adds animated scan lines and expanding rings as a visual effect.", 0.035, "Medium"),
        NvcsModuleInfo("Ghost Trail", "Leaves short-lived markers behind moving image regions.", 0.04, "Medium"),
        NvcsModuleInfo("Power Saver", "Reduces overall battery drain and allows slower processing.", -0.05, "Power saving"),
        NvcsModuleInfo("LIDAR Scan", "Simulates a laser-like edge sweep. A regular webcam cannot measure true depth.", 0.075, "High"),
        NvcsModuleInfo("Object Classification", "Uses YOLO to label visible objects when the model is available.", 0.135, "Very high"),
        NvcsModuleInfo("Motion Vector", "Draws arrows showing the estimated direction of moving regions.", 0.045, "Medium"),
        NvcsModuleInfo("Coordinate Grid", "Overlays an X/Y grid for visual position reference.", 0.008, "Very low"),
        NvcsModuleInfo("Light Analyzer", "Measures average image brightness and displays a simple light reading.", 0.025, "Low"),
        NvcsModuleInfo("Proximity Alert", "Warns when a detected region appears large or grows quickly in the image; this is not real-world ranging.", 0.035, "Medium"),
        NvcsModuleInfo("Interference Detector", "Flags large brightness changes or unusually blurred frames.", 0.035, "Low-medium"),
        NvcsModuleInfo("Session Timeline", "Shows recent scanner events recorded during this session.", 0.012, "Very low"),
        NvcsModuleInfo("Split View", "Shows original and processed images side by side for comparison.", 0.025, "Low-medium"),
        NvcsModuleInfo("Detail Enhancer", "Applies sharpening and contrast to make visible edges easier to inspect.", 0.045, "Medium"),
        NvcsModuleInfo("Overload Protection", "Slows expensive detection updates when FPS drops to help prevent lag.", 0.008, "Very low"),
        NvcsModuleInfo("Track History", "Shows recent positions of moving regions as fading history markers.", 0.03, "Low-medium"),
        NvcsModuleInfo("Minimal HUD", "Hides extra HUD text for a cleaner camera view.", 0.005, "Very low"),
        NvcsModuleInfo("LIDAR 2.0 — Point Cloud", "Accumulates sampled edge points into a green point-cloud effect. Webcam imagery does not provide true depth.", 0.095, "Very high"),
        NvcsModuleInfo("Surface Reconstruction", "Builds an approximate wireframe from image edges; it is a 2D visual approximation, not real 3D geometry.", 0.08, "High"),
        NvcsModuleInfo("Multi-Pass Scan", "Accumulates multiple simulated LIDAR passes and shows scan progress. Press R to reset the scan.", 0.07, "High"),
        NvcsModuleInfo("Pixel Inspector", "Move the mouse over the camera image to inspect pixel RGB, brightness, and coordinates.", 0.008, "Very low"),
        NvcsModuleInfo("Pixel Counter", "Counts pixels similar to the color sampled with a left-click on the camera image.", 0.02, "Low"),
        NvcsModuleInfo("Object Ruler", "Click two points on the camera image to measure their pixel distance. Press C to clear the measurement.", 0.008, "Very low"),
        NvcsModuleInfo("Resolution Destroyer", "Pixelates the live feed at selectable block sizes. Press [ and ] to change pixelation.", 0.012, "Very low"),
        NvcsModuleInfo("Binary Vision", "Maps image brightness to a scrolling matrix of 0 and 1 characters.", 0.035, "Low-medium"),
        NvcsModuleInfo("Brightness Seismograph", "Plots recent average brightness values as a live mini graph.", 0.012, "Very low"),
        NvcsModuleInfo("Quantum Scanner", "A fictional glitch effect that shifts small image tiles using real image-processing operations.", 0.05, "Medium"),
        NvcsModuleInfo("Ransom Module", "Requires Motion Tracker. During Blocka90, any visible cyan tracker rectangle counts as movement: A-90 jumpscare (1 second), Stareman2 flash, then download + coin challenge. If no cyan tracker rectangle appears, Stareman3 flashes briefly and the red static/event ends. The 1:30 coin timer can still trigger the three-minute fading static on timeout. This is a simulated in-app event; no files are downloaded.", 0.065, "Medium-high"),
        NvcsModuleInfo("Error Module", "Distorts the camera with an animated purple-to-pink color corruption, RGB split, glitch strips and scanlines. Loops BEEPERROR while enabled.", 0.045, "Medium"),
        NvcsModuleInfo("Space Module", "Green low-noise vision. R toggles a movement + LIDAR-style point-cloud scan (Space_E). Q toggles night vision with object and estimated-distance detection (c-moon gravity shift). Distances are estimates from a normal webcam, not real depth.", 0.04, "Medium-high"),
        NvcsModuleInfo("Anomaly Module", "Anomaly hunt minigame. Press A to begin, then click a moving region outlined in magenta before the 15-second timer ends. Uses camera motion regions; it does not identify supernatural events.", 0.035, "Medium"),
        NvcsModuleInfo("Power Module", "Power management utility. Press P to activate a 15-second power shield that greatly reduces battery drain. It has a 30-second cooldown; the status appears above the battery meter.", 0.015, "Low")
    )

    fun find(name: String): NvcsModuleInfo? = all.firstOrNull { it.name == name }
}
