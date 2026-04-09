---

# Quick Start Guide

## For End Users

### Prerequisites
- Android 8.0 or higher
- NFC-enabled Android device
- Good Display NFC-D3-029 Driver Board (https://www.good-display.com/product/561.html)
- Compatible e-ink display (2.7", 2.9", 3.7", or 4.2" - Mono or Color)
- Ballistic rangecard CSV file from AB Quantum or Kestrel Link (can be directly exported for these applications into Open E-Dope)

### Installation
1. Download the latest APK from the Releases page
2. Transfer the APK to your Android device or email it to yourself
3. On your Android device: Open file manager → Find the APK file → Tap to install
4. Grant requested permissions (NFC, Storage, Vibrate)
5. Launch the app and start importing rangecard data

### First Steps
1. Load a CSV File: Tap "Choose File" to select your rangecard CSV export
2. Review Data: The table will display all rows from your CSV
3. Configure Display: Go to Settings to select your e-ink display model
4. Transfer to NFC: Tap "Send to NFC Tag" and place your device near an NFC tag
5. Monitor Progress: Watch the progress dialog until transfer completes

---

# For Developers

### Prerequisites
- Python 3.9+
- Kivy 2.1.0+
- KivyMD 0.104+
- Java Development Kit (JDK) 8+
- Android SDK (for APK building)
- Buildozer (for APK compilation)

### Installation

1. Clone the repository:
   git clone https://github.com/Jgriffiths92/Open_E-Dope.git
   cd Open_E-Dope

2. Create virtual environment:
   python -m venv venv
   source venv/bin/activate  # On Windows: venv\Scripts\activate

3. Install dependencies:
   pip install -r requirements.txt

4. Run on desktop (testing only):
   python main.py

---

# Project File Structure

```
Open_E-Dope/
├── main.py                      # Core application code (3000+ lines)
├── layout.kv                    # Kivy UI definitions
├── circularprogressbar.py       # Custom progress bar widget
├── requirements.txt             # Python dependencies
├── buildozer.spec              # APK build configuration
├── Kivy_App_To_APK.ipynb       # Google Colab build instructions
│
├── app/
│   └── src/main/java/com/openedope/open_edope/
│       ├── NfcHelper.java       # NFC write operations
│       └── NfcProgressListener.java  # Transfer progress callback
│
├── assets/
│   ├── bitmap/                 # Generated bitmap outputs
│   ├── CSV/                    # CSV file storage
│   ├── fonts/                  # Monospace font for rendering
│   └── images/                 # App icons and graphics
│
├── private_storage/
│   ├── settings.ini            # User preferences (created at runtime)
│   └── CSV/                    # Event folders with saved CSV files
│
└── README.md                   # This file
```

---

# Build Instructions

## Desktop Testing

```bash
pip install -r requirements.txt
python main.py
```

Note: NFC features will be disabled on desktop; other functionality fully testable.

---

## Building the APK

### Method 1: Google Colab (Recommended for Windows users)

1. Open Kivy_App_To_APK.ipynb
2. Copy the notebook URL to Google Colab
3. Run all cells in order
4. Download the generated APK from Colab output
5. Transfer to Android device and install

---

### Method 2: Local Build (Linux/Mac)

Requirements:
- Buildozer: `pip install buildozer`
- Java SDK: `sudo apt-get install openjdk-11-jdk`
- Android SDK: Download via buildozer (automatic)

Build Steps:
```bash
buildozer android debug
adb install bin/open_edope-0.1-debug.apk
```

---

# Hardware Setup Guide

## Good Display NFC E-Ink Display Setup

### Required Components
1. Good Display NFC-D3-029 (https://www.good-display.com/product/561.html)
2. Android Device with NFC support (Pixel, Samsung Galaxy, etc.)
3. USB Cable for data connection (optional, for debugging)

### Connection Steps

1. Physical Connection:
   - Connect display to NFC controller via ribbon cable (included)

2. Display Identification:
   - In app Settings, select the matching display model:
     - Good Display 2.9": 296×128 pixels
     - Good Display 3.7": 480×280 pixels (recommended)
     - Good Display 4.2": 800×480 pixels

3. Orientation Configuration:
   - Choose Portrait (default) or Landscape
   - Bitmap will render to match selected orientation

4. Test Connection:
   - Load a CSV file or enter manual data
   - Hold device near NFC tag (1-2 cm)
   - Monitor progress dialog
   - Success: Display updates with rangecard data

---

# NFC Tag Setup

## Preparing NFC Tags

### Tag Requirements
- Good Display NFC-D3-029 (https://www.good-display.com/product/561.html)
- A Good Display E-Ink display or compatible NFC e-ink display

### Compatible Displays
- Good Display 2.7" Mono (264×176 pixels)
- Good Display 2.9" Mono (296×128 pixels)
- Good Display 3.7" Mono (480×280 pixels) **Recommended**
- Good Display 4.2" Mono (800×480 pixels)
- Good Display 2.7" Color (264×176 pixels)
- Good Display 2.9" Color (296×128 pixels)
- Good Display 3.7" Color (480×280 pixels)
- Good Display 4.2" Color (800×480 pixels)

### Writing Rangecard Data

1. Load CSV file in app directly from AB Quantum or Kestrel Link via the export functionality or manually enter data
2. Hold Android device near NFC tag (1-2 cm)
3. Wait for "Transfer Complete" message and "Refreshing" dialog to close
4. Data is now on the tag

---

# Troubleshooting & FAQs

## General Issues

### App crashes on startup
Solution: 
- Clear app cache: Settings > Apps > Open E-Dope > Storage > Clear Cache
- Reinstall the app
- Check device has minimum 100MB free storage

### "NFC is not available"
Solution: Enable NFC in device settings or use a different device

---

## CSV Import Issues

### "No data displayed in table"
Solution:
- Verify CSV format matches AB Quantum or Kestrel Link export
- Manually re-export from original ballistics software
- Check first 6 lines are metadata (not data rows)

---

## NFC Transfer Issues

### "NFC tag not detected"
Troubleshooting:
1. Verify NFC is enabled: Settings > NFC > On
2. Try different tag location on device back (varies by model)
3. Hold steady at 1-2cm distance
4. Try different NFC tag (may be damaged)

### "Transfer timeout / Progress stuck"
Solutions:
- Keep device near tag entire duration (don't move)
- Verify tag is writable (not locked/protected)
- Try smaller rangecard (fewer rows)
- Restart app and try again

---

## FAQ

Q: Can I use this on iOS?
A: Not currently. Kivy supports iOS but requires Mac/XCode. Contributions welcome!

Q: Do I need internet connection?
A: No. App works fully offline after initial install.

Q: Can I modify the CSV before exporting to NFC?
A: Yes! Use the manual data input form or edit CSV in text editor before importing (but NOT recommended).

Q: How often should I update my rangecard?
A: Depends on environmental changes. Typically per event/location change.

Q: Can I export bitmap to file?
A: Yes, bitmaps are saved to [storage]/assets/bitmap/ folder.

Q: What's the maximum rangecard size?
A: Depends on display and font size, typically 1-10 rows of data. The font is resized to match your display automatically.

---

# Contributing Guidelines

We welcome contributions! Here's how to help:

## Reporting Bugs

1. Check existing issues to avoid duplicates
2. Create new issue with:
   - Device model and Android version
   - Steps to reproduce
   - Expected vs. actual behavior
   - Logcat output (if app crash)

---

## Contributing Code

1. Fork the repository
2. Create a branch: `git checkout -b feature/your-feature-name`
3. Make changes and add comments
4. Test thoroughly on device and desktop
5. Push branch: `git push origin feature/your-feature-name`
6. Open Pull Request with description

### Code Standards
- Follow PEP 8 for Python
- Add docstrings to new methods
- Include inline comments for complex logic
- Test on both desktop and Android

---

# License

This project is licensed under the MIT License - see the LICENSE file for details.

You're free to use, modify, and distribute this software with attribution.

---

# Contact & Support

## Getting Help

### Discord Server
Join our community for real-time help:
https://discord.gg/KrpdjWS8zw

- #general - General discussion
- #support - Technical help
- #development - Contributing & development
- #showcase - Share your rangecards

### GitHub Issues
Report bugs or suggest features:
https://github.com/Jgriffiths92/Open_E-Dope/issues

---

## Project Links

- GitHub: https://github.com/Jgriffiths92/Open_E-Dope
- Releases: https://github.com/Jgriffiths92/Open_E-Dope/releases
- Discord: https://discord.gg/KrpdjWS8zw
- Good Display: https://www.good-display.com

---

## Credits

### Libraries & Resources
- Kivy - UI Framework (https://kivy.org)
- KivyMD - Material Design (https://kivymd.io)
- PIL/Pillow - Image processing (https://python-pillow.org)
- Good Display - E-ink display kits (https://www.good-display.com)
- Buildozer - APK building (https://github.com/kivy/buildozer)

### Special Thanks
- Ballistics community for feature feedback
- Beta testers for device compatibility verification

---

## Changelog

### Version 0.1.0 (Current)
- Initial release
- CSV import from AB Quantum and Kestrel Link
- NFC tag writing
- Multiple display model support
- Settings persistence
- Manual data input
- Bitmap generation for e-ink

### Upcoming (v0.2.0)
- Color display support
- Unit tests
- Performance optimizations

---

Last Updated: April 2026
Status: Active Development
