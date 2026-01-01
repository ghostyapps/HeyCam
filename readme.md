# HeyCam 📸

![HeyCam Screenshot](screenshots/screenshot_1.png)

## The Story Behind HeyCam

I was tired of every phone's native camera app constantly processing photos behind the scenes. AI enhancements, aggressive noise reduction, over-sharpening - I wondered: **could I build a camera app with zero processing?**

My goal was simple: capture what the lens and sensor actually see, with minimal interference. I especially didn't want AI "improving" my photos. To achieve true control and zero processing, I created **HeyCam**.

## What is HeyCam?

HeyCam is a minimalist camera app with manual controls that lets you capture photos in both **JPEG** and **RAW (DNG)** formats. You have full control over your hardware, putting the power back in your hands.

Since JPEG files still undergo some processing by Android's algorithms, I added **RAW support** for true zero-processing photography. 

**Want true zero processing?** Switch to **Manual mode** and shoot in **RAW format**. The photos might look flat or low-quality at first - but that's the point! You're getting the unprocessed image data straight from the sensor.

## Features

### ⏱️ Professional Intervalometer
* **Burst & Interval Control:** Dedicated menu to set countdown intervals (1-10s) and photo count (1-10 shots) for time-lapse or group shots.
* **Auditory Feedback:** Integrated Sound Manager for countdown beeps, shutter feedback, and completion chimes.
* **Floating HUD:** High-visibility countdown overlay distinct from the viewfinder.

### 🔴 Nothing Phone (3) Exclusive: Glyph Matrix
* **Real-Time Countdown:** Integrated `com.nothing.ketchum` SDK to display the timer directly on the rear LED Matrix.
* **Smart Orientation:** Glyph numbers rotate automatically based on device orientation.
* **Custom Completion Pattern:** Unique visual animation upon finishing a sequence.

### 🖼️ Creative Suite: Frames & LUTs
* **Custom LUT Support:** Import standard `.cube` files for cinematic color grading.
* **Frame Engine:** Support for `.png` overlays with smart rotation that adapts to image orientation.
* **RAW Safety:** LUTs, Frames, and EV controls are automatically disabled in RAW mode to preserve data integrity.

### 🎛️ UI/UX Overhaul
* **Active Auto Mode:** Replaced passive auto with an **Exposure Compensation (EV)** panel for precise brightness control.
* **Floating Layout Engine:** Controls now float perfectly centered regardless of screen aspect ratio.
* **WYSIWYG Preview:** Fixed viewfinder scaling to match the sensor output 1:1, removing distortion correction cropping.

## Modes & Controls

### Auto Mode (Enhanced)
- Active Exposure Compensation (EV) with hardware-specific step sizes.
- Replaces ISO/Shutter wheels with intuitive (+) and (-) buttons.

### Manual Mode
- Full control over shutter speed and ISO via interactive slider.
- Tap to focus only - exposure stays locked to your settings.

## Technical Details
- **Rear camera only** - no front camera access.
- **Main camera sensor only** - Access to ultra-wide or telephoto lenses is limited due to API restrictions.
- **Zero processing in RAW mode** - All automatic corrections disabled (Noise reduction, Edge enhancement, Hot pixel correction, Lens distortion correction).

## Why RAW?
RAW files capture pure sensor data. They may look flatter or noisier, but they provide **unprocessed reality**. Edit these in Lightroom or Snapseed for maximum flexibility.

## Installation
1. Download the latest APK from [Releases](../../releases).
2. Enable "Install from unknown sources" in your Android settings.
3. Install and grant camera permissions.

## Technical Stack
- **Language**: Java
- **Camera API**: Camera2 API
- **Key Libraries**: `com.nothing.ketchum` (Glyph SDK)

---

**Note**: This app is designed for photographers who want full manual control. If you prefer point-and-shoot convenience with AI enhancements, your phone's native camera app might be a better choice.