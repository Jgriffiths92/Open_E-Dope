A Python Android built in python using Kivy to display CSV data exported from AB Quantum or Kestrel Link Ballistics on NFC driven e ink 2.9, 3.7 and 4.2in displays.
To compile APK file for Android simply open the Kivy_App_To_APK.ipynb in Google colab and run all code snippets. Alternatively you can download the latest release apk from release s page.
This project is designed to be compatible with Good Display NFC-Driven 2.7"/ 2.9"/ 3.7"/ 4.2" E-Ink Display Development Kit, NFC-D3-029 https://www.good-display.com/product/561.html
and the compatible screens listed are:

Durable 2.9 inch E Ink screen fast refresh, SPI e-paper display, GDEY029T94 https://www.good-display.com/product/389.html

E Ink display tablet 2.9 inch Tri-color ePaper display supporting monochrome Partial update 296x128, GDEY029Z95 https://www.good-display.com/product/527.html

E-Paper Module 2.9 inch four-color E-ink screen 296x128 GoodDisplay® GDEY029F51 https://www.good-display.com/product/468.html

E Ink display 2.9'' 4-color E-paper screen high solutionJD79667 384x168, GDEY029F51H https://www.good-display.com/product/464.html

E-Paper Display Arduino 3.7 inch E-ink Screen 416x240 Pixels, Fast Update GDEY037T03 https://www.good-display.com/product/437.html (recomended)

E-Ink Monitor 3.7 Inch Color E Ink Tablet 416x240 E Paper Display, GDEY037Z03 https://www.good-display.com/product/413.html

E-Ink Display 3.7-inch Black, White, Yellow, and Red E Ink Display 416x240, GDEM037F51 https://www.good-display.com/product/505.html

E-Ink Technology 4.2 inch e-ink display high rate refresh 400x300 SPI, GDEY042T81 https://www.good-display.com/product/386.html

E-paper display 4.2 inch tri-color e-ink epd display supporting monochrome partial update, GDEY042Z98 https://www.good-display.com/product/387.html

At this stage color screens whilst still compatible will only display theoreticly in black and white.
latest news and more info is available on Discord https://discord.gg/KrpdjWS8zw

---

# Code Documentation & Structure

## Overview
Open E-Dope is a Kivy/KivyMD application for managing ballistic rangecards on Android devices with NFC tag support. The application converts CSV rangecard data into e-ink display bitmaps and transfers them to NFC tags for use in the field.

## Code Organization

### Top-Level Sections
The codebase is organized into 6 major sections:

1. **Imports & Global Setup** - Module imports, global variables, and configuration
2. **KV Builder / UI Customization** - Kivy layout customization and theming
3. **Screen / Widget Classes** - Screen definitions (HomeScreen, SavedCardsScreen, etc.) and custom widgets
4. **NFC Progress Listener** - Java callback for NFC transfer progress monitoring
5. **MainApp Class** - Core application logic and event handlers (50+ methods)
6. **Storage / Utility Helpers** - Top-level helper functions for file I/O and image processing

### MainApp Class Subsections
The MainApp class is further organized into 12 subsections:

- **NFC / UI Action Handlers** - NFC button clicks and UI navigation
- **Android Permissions** - Permission request and result handling
- **App Initialization & State** - State variable initialization
- **NFC Transfer** - CSV-to-bitmap conversion and NFC tag writing
- **App Lifecycle & UI Build** - App startup and UI construction
- **Table / Data Handling** - Table display and row management
- **CSV File Loading / Parsing** - CSV reading and data preprocessing
- **Settings / Configuration** - User preference persistence
- **Bitmap Generation** - E-ink display image rendering
- **NFC / Intent Handling** - Android intent processing and NFC tag detection
- **UI Controls / Menus** - Dropdown menus and settings screens
- **Display / Orientation Settings** - Display resolution and layout management
- **Storage Path Helpers** - File system path resolution for Android/desktop

---

## Key Methods & Documentation

### CSV Data Pipeline

#### `read_csv_to_dict(file_or_path)`
**Purpose:** Parse CSV rangecard export files and map them to standard columns.

**Key Features:**
- Handles both file paths and file-like objects (StringIO, BytesIO)
- Skips the first 6 metadata rows (rangecard format standard)
- Maps dynamically to fixed column names: Target, Range, Elv, Wnd1, Wnd2, Lead
- Uses latin-1 encoding to handle extended ASCII from Excel exports
- Stops parsing at "Stage Notes:" footer marker

**Returns:** List of dictionaries mapping column names to cell values

---

#### `preprocess_data(data)`
**Purpose:** Fix column alignment issues that occur in some CSV exports.

**Algorithm:**
- Detects if Target column contains numeric values > 40 (typical rifle range indicator)
- If numeric value detected: shifts all columns one position right (misaligned export)
- If no numeric value: keeps row as-is

**Example:**
```
Input (misaligned):   Target=100, Range=100, Elv=1.2, Wnd1=0.5, ...
Output (corrected):   Target=100, Range=100, Elv=1.2, Wnd1=0.5, ...
```

---

#### `display_table(data)`
**Purpose:** Render CSV data as a formatted table in the home screen.

**Filtering Logic:**
- Removes rows where all values (except Target) are "---" (empty placeholders)
- Removes rows where all values are "0" (malformed imports)
- Filters columns based on user visibility settings

**Output:** ScrollView with GridLayout containing table data

---

### File I/O & Storage

#### `copy_assets_to_internal_storage()`
**Purpose:** Copy asset files from APK to writable storage (Android-specific requirement).

**Platform Differences:**
- **Android:** Uses AssetManager via reflection to access APK assets
  - Recursively handles directories
  - Opens asset files and copies to internal storage
- **Desktop:** Copies from local `assets/CSV` folder using standard file I/O

**Key Path:**
```
Android: /data/data/[app_package]/files/CSV/
Desktop: [project_root]/assets/CSV/
```

---

#### `populate_swipe_file_list(target_dir, sort_by, reverse)`
**Purpose:** Populate the file browser with sortable, filterable file/folder list.

**Features:**
- Shows parent directory navigation ("Back" button)
- Skips hidden files (starting with '.')
- Filters by search text if present
- **Sorting Options:** name, date, type (all with reverse option)
- Groups folders above files

---

#### `delete_file_or_folder(path)`
**Purpose:** Delete files/folders and refresh the file browser view.

**Workflow:**
1. Validate path and get absolute path
2. Delete folder recursively or single file
3. Provide user feedback via toast
4. Clear table data and refresh file list
5. Return to saved_cards screen

---

### Bitmap Generation (E-Ink Optimization)

#### `csv_to_bitmap(csv_data, output_path=None)`
**Purpose:** Convert CSV rangecard data to 1-bit bitmap for e-ink display output.

**Process:**
1. Preprocess and filter data (remove empty/zero rows)
2. Select columns based on user visibility settings
3. Dynamically calculate maximum font size that fits all data
4. Create PIL Image in '1' mode (black/white)
5. Render stage name, headers, data rows, and stage notes
6. Wrap stage notes text to fit display width
7. Pack image using column-major byte order (e-ink optimized)
8. Save as BMP file

**Output Resolution:** Based on selected display model
```
Good Display 3.7": 480x280 pixels (portrait) or 280x480 (landscape)
Good Display 2.9": 296x128 pixels (portrait)
```

---

#### `pack_image_column_major(img)`
**Purpose:** Pack 1-bit image into bytes using column-major order for e-ink displays.

**Format Details:**
- **Direction:** Right-to-left, top-to-bottom (e-ink scanning order)
- **Bit Packing:** 8 vertical pixels per byte
- **Bit Values:** 1 = black pixel, 0 = white pixel
- **Output:** Bytes suitable for Waveshare and similar e-ink controllers

**Example:**
```
Column 0, pixels [0-7] → byte 0
Column 0, pixels [8-15] → byte 1
Column 1, pixels [0-7] → byte 2
...
```

---

### NFC Operations

#### `on_new_intent(intent)`
**Purpose:** Handle Android intents including NFC tag detection and shared CSV data.

**Intent Processing:**
1. Get intent action to determine trigger type
2. Check for NFC tag detection (EXTRA_TAG parcelable)
3. Log tag technologies detected by Android
4. If manual data present: combine with existing data before transfer
5. Trigger bitmap generation and NFC write
6. Handle shared data from other apps (SEND/VIEW actions)

**NFC Tag Detection:** Handles multiple Android NFC actions:
- `android.nfc.action.TAG_DISCOVERED`
- `android.nfc.action.NDEF_DISCOVERED`
- `android.nfc.action.TECH_DISCOVERED`

---

#### `send_csv_bitmap_via_nfc(intent)`
**Purpose:** Generate bitmap from current data and write to NFC tag via Java callback.

**Validation:**
- Checks for valid current_data
- Validates stage_name field is filled
- Confirms NFC adapter is initialized

**Data Transfer:**
1. Generate bitmap from current CSV data
2. Convert bitmap to bytes using column-major packing
3. Call Java NfcHelper callback with byte data
4. Monitor progress via NFC progress listener
5. Update UI on completion/error

---

### Manual Data Input

#### `show_manual_data_input()`
**Purpose:** Display dynamic manual data entry form with add/delete row functionality.

**Layout Structure:**
```
┌─────────────────────────────────┐
│  Target | Range | Elv | Wnd1... │  ← Input row 1
│  Target | Range | Elv | Wnd1... │  ← Input row 2
│         (ScrollView)            │
│  Target | Range | Elv | Wnd1... │  ← Input row N
├─────────────────────────────────┤
│  [ADD ROW]    [DELETE ROW]      │  ← Button row
├─────────────────────────────────┤
│  [80dp spacer for keyboard]     │
└─────────────────────────────────┘
```

**Features:**
- Dynamic field generation based on `available_fields` config
- Auto-focus first field in new rows
- Scroll to buttons when field is focused
- Keyboard spacer prevents overlay

---

#### `add_data_row(rows_layout, focus_row=True)`
**Purpose:** Create a new data entry row with configured columns.

**Field Creation:**
- Creates MDTextField for each visible column
- Sets hint text from `available_fields` config
- Binds focus event to auto-scroll to buttons
- Stores field references for later data retrieval

**Keyboard Navigation:** Updates field tab order after adding row

---

### Settings & Configuration

#### `save_settings()`
**Purpose:** Persist user preferences to disk for next session.

**Settings Saved:**
- **Display:** Model, orientation, standalone mode
- **Column Visibility:** show_lead, show_range, show_2_wind_holds
- **File Browser:** sort_type (date/name/type), sort_order (asc/desc)
- **App State:** delete_folders_after, manage_data_dialog_shown

**Config File Location:**
```
Android: /data/data/[app_package]/files/settings.ini
Desktop: [project_root]/settings.ini
```

---

#### `load_settings()`
**Purpose:** Restore user preferences from previous session.

**Defaults (First Run):**
```
Display Model: "Good Display 3.7-inch"
Orientation: "Portrait"
Sort Type: "date"
Sort Order: "asc"
Column Visibility: All enabled (True)
```

**UI Update:** After loading, updates `available_fields` dictionary to reflect visibility settings

---

### Display & Resolution Management

#### Display Models
```
Good Display 3.7":  480×280 pixels (default)
Good Display 2.9":  296×128 pixels
Waveshare 4.2":     800×480 pixels
Custom:             User-defined resolution
```

#### Orientation Handling
- **Portrait:** Width < Height (default for 3.7" display)
- **Landscape:** Height < Width (default for 4.2" display)
- Bitmap rendered to match selected orientation

---

## Data Flow Diagrams

### CSV Import & Display
```
CSV File
   ↓
read_csv_to_dict()      → Parse rows, skip headers, map columns
   ↓
preprocess_data()       → Fix column alignment if needed
   ↓
display_table()         → Filter empty rows, render in GridLayout
   ↓
User sees table on HomeScreen
```

### NFC Transfer Pipeline
```
Current Data (CSV or Manual)
   ↓
csv_to_bitmap()         → Generate image, calculate font size
   ↓
pack_image_column_major() → Convert to e-ink byte format
   ↓
send_csv_bitmap_via_nfc() → Call Java callback with bytes
   ↓
NFC Tag Write           → Java handles actual NFC write
   ↓
NFC Progress Update     → Listener reports completion status
   ↓
User sees success/error toast
```

### Manual Data Entry & Save
```
show_manual_data_input()  → Display form with fields
   ↓
User enters data across rows
   ↓
add_data_row()            → Add more rows as needed
   ↓
add_manual_data()         → Combine manual rows with existing data
   ↓
save_data()               → Write CSV file to disk
   ↓
File saved to [storage]/CSV/[event_folder]/[stage_name].csv
```

---

## Android-Specific Implementation

### Permissions Required
```
NFC                          - For tag reading/writing
READ_EXTERNAL_STORAGE        - For file access
WRITE_EXTERNAL_STORAGE       - For CSV/bitmap output
VIBRATE                      - For haptic feedback on NFC transfer
```

### Intent Binding
```python
# Bind to activity for intent events
from android import activity
activity.bind(on_new_intent=self.on_new_intent)
```

### Storage Paths
```
Private (App-only):     /data/data/[package]/files/
External (Shared):      /storage/emulated/0/Android/data/[package]/files/
Assets (APK):           assets/ (requires AssetManager copy)
```

### NFC Adapter Initialization
```python
NfcAdapter.getDefaultAdapter(mActivity)
PendingIntent.getActivity()
enableForegroundDispatch()  # Register for NFC intents
```

---

## Global Variables

```python
show_lead           # Display Lead/Drift column (bool)
show_range          # Display Range column (bool)
show_2_wind_holds   # Display Wnd2 column (bool)
filechooser         # FileChooserListView reference
is_android()        # Kivy Android environment check
mActivity           # Android PythonActivity reference
```

---

## Configuration File Format (settings.ini)

```ini
[Settings]
display_model = Good Display 3.7-inch
orientation = Portrait
standalone_mode = False
show_lead = True
show_range = True
show_2_wind_holds = True
sort_type = date
sort_order = asc
delete_folders_after = never
manage_data_dialog_shown = False
```

---

## Error Handling

### Common Issues & Solutions

**"stage_notes is not defined"** → Scoping issue in find_max_font_size()
- Variables need to be passed as parameters or accessed via self.root

**"NFC is not available on this device"** → Expected on non-Android platforms
- App gracefully disables NFC features on desktop

**"Asset not found"** → APK assets not copied to internal storage
- Automatically handled by copy_assets_to_internal_storage()

---

## Dependencies & Libraries

```python
# UI Framework
kivy                 - Cross-platform UI
kivymd              - Material Design for Kivy

# Image Processing
PIL (Pillow)        - Bitmap generation and manipulation

# File I/O
csv                 - CSV parsing
configparser        - Settings persistence
os, shutil          - File system operations

# Android Integration
pyjnis             - Java interoperability
android             - Android-specific APIs

# Utilities
Clock               - Kivy scheduling
toast               - Android notifications
```

---

## Future Improvement Notes

- Implement batch NFC transfers for multiple tags
- Create unit tests for CSV parsing and bitmap generation
- Add progress persistence for interrupted operations
