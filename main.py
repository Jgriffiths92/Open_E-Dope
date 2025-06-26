import csv
import itertools
import time
from kivy.lang import Builder
from kivy.uix.screenmanager import ScreenManager, Screen
from kivymd.app import MDApp
from kivymd.uix.datatables import MDDataTable
from kivy.metrics import dp
from plyer import filechooser
from kivy.uix.label import Label
from kivymd.uix.menu import MDDropdownMenu
from kivymd.uix.snackbar import Snackbar
from kivymd.uix.dialog import MDDialog
from kivymd.uix.button import MDFlatButton
from kivymd.uix.button import MDRaisedButton
from kivy.uix.boxlayout import BoxLayout
import os
from kivymd.uix.textfield import MDTextField
from PIL import Image, ImageDraw, ImageFont
import platform
from kivy.config import ConfigParser
from configparser import ConfigParser
from kivy.core.window import Window
import shutil
from plyer import notification
from kivy.clock import Clock
from kivy.uix.filechooser import FileChooserListView
import shutil
from kivymd.uix.dialog import MDDialog
from circularprogressbar import CircularProgressBar
from kivy.uix.boxlayout import BoxLayout
from kivy.lang import Builder
from kivy.app import App
from kivymd.toast import toast
from kivy.properties import StringProperty


# Ensure the soft keyboard pushes the target widget above it
Window.softinput_mode = "resize"

try:
    from android import mActivity
    from jnius import autoclass, cast
    from android.permissions import request_permissions, Permission
except ImportError:
    mActivity = None  # Handle cases where the app is not running on Android
    autoclass = None  # Handle cases where pyjnius is not available
    request_permissions = None
    Permission = None
try:
    from jnius import autoclass, cast
    NfcAdapter = autoclass('android.nfc.NfcAdapter')
    Ndef = autoclass('android.nfc.tech.Ndef')
    NdefFormatable = autoclass('android.nfc.tech.NdefFormatable')
    MifareClassic = autoclass('android.nfc.tech.MifareClassic')
    MifareUltralight = autoclass('android.nfc.tech.MifareUltralight')
except ImportError:
    autoclass = None
    NfcAdapter = None
    Ndef = None
    NdefFormatable = None
    MifareClassic = None
    MifareUltralight = None


def is_android():
    """Check if the app is running on an Android device."""
    try:
        from android import mActivity
        print("Running on Android")
        # Print if these modules are imported
        print("android imported:", 'mActivity' in globals() and mActivity is not None)
        print("jnius imported:", 'autoclass' in globals() and autoclass is not None)
        return True
    except ImportError:
        return False


# Import the nfc module if not running on Android
if not is_android():
    try:
        import nfc
    except ImportError:
        nfc = None  # Handle cases where the nfc module is not available

# Change color of the filechooser
Builder.load_string('''

<FileChooserListView>:
    # --------------------
    # ADD BACKGROUND COLOR
    # --------------------
    canvas.before:
        Color:
            rgb: 1, 1, 1
        Rectangle:
            pos: self.pos
            size: self.size
    layout: layout
    FileChooserListLayout:
        id: layout
        controller: root

[FileListEntry@FloatLayout+TreeViewNode]:
    locked: False
    entries: []
    path: ctx.path
    # FIXME: is_selected is actually a read_only treeview property. In this
    # case, however, we're doing this because treeview only has single-selection
    # hardcoded in it. The fix to this would be to update treeview to allow
    # multiple selection.
    is_selected: self.path in ctx.controller().selection

    orientation: 'horizontal'
    size_hint_y: None
    height: '48dp' if dp(1) > 1 else '24dp'
    # Don't allow expansion of the ../ node
    is_leaf: not ctx.isdir or ctx.name.endswith('..' + ctx.sep) or self.locked
    on_touch_down: self.collide_point(*args[1].pos) and ctx.controller().entry_touched(self, args[1])
    on_touch_up: self.collide_point(*args[1].pos) and ctx.controller().entry_released(self, args[1])
    BoxLayout:
        pos: root.pos
        size_hint_x: None
        width: root.width - dp(10)
        Label:
            # --------------
            # CHANGE FONT COLOR
            # --------------
            color: 0, 0, 0, 1
            id: filename
            text_size: self.width, None
            halign: 'left'
            shorten: True
            text: ctx.name
        Label:
            # --------------
            # CHANGE FONT COLOR
            # --------------
            color: 0, 0, 0, 1
            text_size: self.width, None
            size_hint_x: None
            halign: 'right'
            text: '{}'.format(ctx.get_nice_size())


<MyWidget>:
    FileChooserListView
''')


# Define Screens
class HomeScreen(Screen):
    pass


class SavedCardsScreen(Screen):
    def on_enter(self):
        try:
            print("File and folder list refreshed on screen enter.")
            app = App.get_running_app()
            # Use the loaded sort_type and sort_order from settings
            reverse = app.sort_order == "desc"
            app.populate_swipe_file_list(sort_by=app.sort_type, reverse=reverse)
        except Exception as e:
            print(f"Error refreshing file and folder list: {e}")

    def sort_filechooser(self, sort_by="name", reverse=False):
        try:
            filechooser = self.ids.filechooser
            filechooser.sort_type = sort_by
            filechooser.sort_order = 'desc' if reverse else 'asc'
            filechooser.sort_dirs_first = True
            filechooser.path = filechooser.path
            print(f"Sorted by {sort_by}, reverse={reverse}")
        except Exception as e:
            print(f"Error accessing filechooser: {e}")

    def open_sort_menu(self, caller):
        from kivymd.uix.menu import MDDropdownMenu
        app = App.get_running_app()
        menu_items = [
            {"text": "Name", "on_release": lambda x="name": app.populate_swipe_file_list(sort_by="name")},
            {"text": "Date", "on_release": lambda x="date": app.populate_swipe_file_list(sort_by="date")},
            {"text": "Type", "on_release": lambda x="type": app.populate_swipe_file_list(sort_by="type")},
        ]
        self.sort_menu = MDDropdownMenu(
            caller=caller,
            items=menu_items,
            width_mult=3,
        )
        self.sort_menu.open()


class ManageDataScreen(Screen):
    delete_option_label = StringProperty("Delete Folders After")  # Default text
    def on_enter(self):
        app = App.get_running_app()
        if not getattr(app, "manage_data_dialog_shown", False):
            self.show_manage_data_dialog()

    def show_manage_data_dialog(self):
        app = App.get_running_app()
        def close_dialog(*args):
            dialog.dismiss()
            app.root.ids.screen_manager.current = "home"  # Go to Home screen

        def ok_and_never_show_again(*args):
            dialog.dismiss()
            app.manage_data_dialog_shown = True
            app.save_settings()

        dialog = MDDialog(
            title="Manage Data",
            type="custom",
            content_cls=Label(
            text="Here you can manage and delete your saved data cards and folders.\nUse With Caution: deleted data cannot be recovered.",
            halign="center",
            valign="middle",
            color=(0, 0, 0, 1),
            size_hint_y=None,
            height="100dp",
            ),
            buttons=[
            MDFlatButton(
                text="BACK",
                on_release=close_dialog
            ),
            MDFlatButton(
                text="OK",
                on_release=ok_and_never_show_again,
                theme_text_color="Custom",          # <-- Add this line
                text_color=(0, 0.4, 1, 1)           # Blue color for OK button
            ),
            ],
        )
        dialog.open()
    def open_delete_option_menu(self, caller):
        options = [
            {"text": "After 1 week", "on_release": lambda: self.set_delete_option("week")},
            {"text": "After 1 month", "on_release": lambda: self.set_delete_option("month")},
            {"text": "After 1 year", "on_release": lambda: self.set_delete_option("year")},
            {"text": "Never", "on_release": lambda: self.set_delete_option("never")},
        ]
        self.delete_menu = MDDropdownMenu(caller=caller, items=options)
        self.delete_menu.open()

    def set_delete_option(self, option):
        app = App.get_running_app()
        labels = {
            "week": "After 1 week",
            "month": "After 1 month",
            "year": "After 1 year",
            "never": "Never",
        }
        app.delete_folders_after = option
        app.delete_option_label = labels.get(option, "Delete Folders After")
        app.save_settings()
        if hasattr(self, "delete_menu"):
            self.delete_menu.dismiss()
        app.delete_old_folders()

    def delete_all_csv_files(self):
        from kivymd.uix.dialog import MDDialog
        from kivymd.uix.button import MDFlatButton

        def confirm_delete(*args):
            app = App.get_running_app()
            csv_dir = app.ensure_csv_directory()
            try:
                for item in os.listdir(csv_dir):
                    item_path = os.path.join(csv_dir, item)
                    if os.path.isdir(item_path):
                        shutil.rmtree(item_path)
                    else:
                        os.remove(item_path)
                print("All files and folders in assets/CSV deleted.")
                toast("All Data Card  files and folders deleted.")
            except Exception as e:
                print(f"Error deleting CSV files: {e}")
                toast(f"Error deleting files: {e}")
            dialog.dismiss()

        dialog = MDDialog(
            title="Confirm Delete",
            text="Are you sure you want to delete ALL Events and Data Cards in? This cannot be undone.",
            buttons=[
                MDFlatButton(
                    text="CANCEL",
                    on_release=lambda x: dialog.dismiss()
                ),
                MDFlatButton(
                    text="DELETE",
                    text_color=(1, 0, 0, 1),
                    on_release=confirm_delete
                ),
            ],
        )
        dialog.open()


class SettingsScreen(Screen):
    pass


class CustomFileChooserListView(FileChooserListView):
    sort_type = "date"  # Default sort by date
    sort_order = "asc"  # Or "desc" if you want newest first

    def _sort_files(self, files):
        sort_type = getattr(self, 'sort_type', 'date')
        reverse = getattr(self, 'sort_order', 'asc') == 'desc'

        def get_date(item):
            try:
                return os.path.getmtime(item[1])
            except Exception:
                return 0

        def get_type(item):
            # Folders first, then by extension
            if os.path.isdir(item[1]):
                return ('', '')
            name, ext = os.path.splitext(item[0])
            return (ext.lower(), item[0].lower())

        if sort_type == 'date':
            key = get_date
        elif sort_type == 'type':
            key = get_type
        else:
            key = lambda item: item[0].lower()

        # Sort all items (folders and files) together
        return sorted(files, key=key, reverse=reverse)


if is_android():
    from jnius import PythonJavaClass, java_method

    class NfcProgressListener(PythonJavaClass):
        __javainterfaces__ = ['com/openedope/open_edope/NfcProgressListener']
        __javacontext__ = 'app'

        def __init__(self, app):
            super().__init__()
            self.app = app

        @java_method('(I)V')
        def onProgress(self, percent):
            # Called from Java with progress (0-100)
            print(f"NFC Progress: {percent}%")
            if self.app and hasattr(self.app, 'update_nfc_progress'):
                # Schedule on main thread to update UI
                from kivy.clock import Clock
                Clock.schedule_once(lambda dt: self.app.update_nfc_progress(percent))
            else:
                print("Error: NfcProgressListener.app is None or lacks 'update_nfc_progress' method.")

        @java_method('(Ljava/lang/String;)V')
        def onError(self, message):
            print(f"NFC Error from Java: {message}")
            if self.app and hasattr(self.app, 'on_nfc_transfer_error'):
                from kivy.clock import Clock
                Clock.schedule_once(lambda dt: self.app.on_nfc_transfer_error(message))
            else:
                print("Error: NfcProgressListener.app is None or lacks 'on_nfc_transfer_error' method.")


class MainApp(MDApp):
    search_text = ""
    delete_option_label = StringProperty("Delete Folders After")  # Default text
    EPD_INIT_MAP = {
        # Good Display 3.7-inch (UC8171, 240x416)
        "Good Display 3.7-inch": [
            "F0DB00005EA006512000F001A0A4010CA502000AA40108A502000AA4010CA502000AA40108A502000AA4010CA502000AA40108A502000AA4010CA502000AA40103A102001FA10104A40103A3021013A20112A502000AA40103A20102A40103A20207A5", # Main Init
            "F0DA000003F05120", # Screen Cut
            "3" # Number of colors (e.g., 2 for BW, 3 for BWR)
        ],
        # Good Display 4.2-inch (SSD1680, 400x300)
        "Good Display 4.2-inch": [
            "F0DB000063A00603300190012CA4010CA502000AA40108A502000AA4010CA502000AA40102A10112A40102A104012B0101A1021101A103440031A105452B010000A1023C01A1021880A1024E00A1034F2B01A3022426A20222F7A20120A40102A2021001A502000A", # Main Init
            "F0DA000003F00330", # Screen Cut
            "3" # Number of colors
        ],
        # Good Display 2.9-inch (SSD1680, 296x128)
        "Good Display 2.9-inch": [
            "F0DB000067A006012000800128A4010CA502000AA40108A502000AA4010CA502000AA40102A10112A40102A10401270101A1021101A10344000FA1054527010000A1023C05A103210080A1021880A1024E00A1034F2701A30124A3022426A20222F7A20120A40102A2021001A502000A", # Main Init
            "F0DA000003F00120", # Screen Cut
            "3" # Number of colors
        ],
    }
    def get_basename(self, path):
        import os
        return os.path.basename(path)
    def on_nfc_button_press(self, *args):
        """Handle the NFC button press: generate the bitmap from current data."""
        print("NFC button pressed!")
        if not hasattr(self, "current_data") or not self.current_data:
            print("No data loaded to generate bitmap.")
            return
        output_path = self.csv_to_bitmap(self.current_data)
        if output_path:
            print(f"Bitmap generated and saved to: {output_path}")
        else:
            print("Failed to generate bitmap.")

    def update_nfc_progress(self, percent):
        if hasattr(self, "nfc_progress_bar") and self.nfc_progress_bar:
            # If percent is 100, delay the update by 3 seconds
            if percent >= 100:
                Clock.schedule_once(lambda dt: self._finish_nfc_progress(), 3)
            else:
                self.nfc_progress_bar.value = percent

    def _finish_nfc_progress(self):
        if hasattr(self, "nfc_progress_bar") and self.nfc_progress_bar:
            self.nfc_progress_bar.value = 100
        if hasattr(self, "nfc_progress_label"):
            self.nfc_progress_label.text = "Transfer successful!"
            self.nfc_progress_label.color = (0, 0.6, 0, 1) # Green for success
        Clock.schedule_once(lambda dt: self.hide_nfc_progress_dialog(), 1.5)
         # Clear the data table, stage notes, and stage name after success
        Clock.schedule_once(lambda dt: self.clear_table_data())       
        print("PYTHON DEBUG: _finish_nfc_progress completed. self.current_data should be cleared by clear_table_data().")
    def on_nfc_transfer_error(self, error_message="Transfer failed!"):
        print(f"PYTHON DEBUG: on_nfc_transfer_error called with message: {error_message}")
        dialog_updated_successfully = False

        # Attempt to update the existing progress dialog if it's fully formed
        if hasattr(self, "nfc_progress_dialog") and self.nfc_progress_dialog and \
           hasattr(self, "nfc_progress_label") and self.nfc_progress_label:
            self.nfc_progress_label.text = error_message
            self.nfc_progress_label.color = (1, 0, 0, 1)  # Red color for error
            dialog_updated_successfully = True
            
            # Also hide the progress bar itself if it exists
            if hasattr(self, "nfc_progress_bar") and self.nfc_progress_bar:
                self.nfc_progress_bar.opacity = 0 # Hide it to emphasize the error
        
        if not dialog_updated_successfully:
            # Fallback: If the dialog or its label isn't there, show a toast
            toast(f"NFC Error: {error_message}")

        # Ensure any active progress dialog is dismissed after showing the error
        Clock.schedule_once(lambda dt: self.hide_nfc_progress_dialog(), 2.5) # Give time to see the error

        self.current_data = []
        self.manual_data_rows = []
        print("PYTHON DEBUG: on_nfc_transfer_error. self.current_data and manual_data_rows cleared.")
    def show_nfc_progress_dialog(self, message="Transferring data..."):
        # Vibrate for 500ms when the dialog opens (Android only)
        if is_android() and mActivity and autoclass:
            try:
                Context = autoclass('android.content.Context')
                vibrator = mActivity.getSystemService(Context.VIBRATOR_SERVICE)
                # Try to use VibrationEffect if available, otherwise use legacy API
                try:
                    VibrationEffect = autoclass('android.os.VibrationEffect')
                    effect = VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE)
                    vibrator.vibrate(effect)
                    print("Vibrating with VibrationEffect")
                except Exception:
                    vibrator.vibrate(500)
                    print("Vibrating with legacy API")
            except Exception as e:
                print(f"Error vibrating device: {e}")

        if hasattr(self, "nfc_progress_dialog") and self.nfc_progress_dialog:
            self.nfc_progress_dialog.dismiss()
        from kivy.uix.floatlayout import FloatLayout
        from kivy.uix.label import Label

        # Use FloatLayout to allow centering
        box = FloatLayout(size_hint_y=None, height="200dp")

        self.nfc_progress_bar = CircularProgressBar(
            size_hint=(None, None),
            size=(120, 120),
            pos_hint={"center_x": 0.5, "center_y": 0.6},
            max=100,
            value=0,
            thickness=15,
            color=(0.2, 0.6, 1, 1),
            label_color=(0.2, 0.6, 1, 1),  # <-- Add this line
            background_color=(0.9, 0.9, 0.9, 1),
        )
        box.add_widget(self.nfc_progress_bar)

        # Add the label below the progress bar, also centered
        self.nfc_progress_label = Label(
            text=message,
            size_hint=(None, None),
            size=(200, 40),
            pos_hint={"center_x": 0.5, "y": 0.05},
            halign="center",
            valign="middle",
            color=(0, 0, 0, 1),
        )
        self.nfc_progress_label.bind(size=self.nfc_progress_label.setter('text_size'))
        box.add_widget(self.nfc_progress_label)

        self.nfc_progress_dialog = MDDialog(
            title="NFC Transfer",
            type="custom",
            content_cls=box,
            auto_dismiss=False,
        )
        self.nfc_progress_dialog.open()
        """
        Handler for the NFC button press.
        Generates the bitmap from the current CSV data and saves it.
        """
        try:
            if not hasattr(self, "current_data") or not self.current_data:
                print("No data loaded to generate bitmap.")
                return
    
            output_path = self.csv_to_bitmap(self.current_data)
            if output_path:
                print(f"Bitmap generated and saved to: {output_path}")
            else:
                print("Failed to generate bitmap.")
        except Exception as e:
            print(f"Error generating bitmap: {e}")
    def on_permissions_result(self, permissions, grant_results):
        """Handle the result of the permission request."""
        for permission, granted in zip(permissions, grant_results):
            if permission == Permission.NFC:
                if granted:
                    print("NFC permission granted.")
                    self.initialize_nfc()
                else:
                    print("NFC permission denied.")
            elif permission == Permission.READ_EXTERNAL_STORAGE:
                if granted:
                    print("Read external storage permission granted.")
                else:
                    print("Read external storage permission denied.")
            elif permission == Permission.WRITE_EXTERNAL_STORAGE:
                if granted:
                    print("Write external storage permission granted.")
                else:
                    print("Write external storage permission denied.")

    def __init__(self, **kwargs):
        super().__init__(**kwargs)
        self.config_parser = ConfigParser()  # Initialize ConfigParser
        private_storage_path = self.get_private_storage_path()
        self.config_file = os.path.join(private_storage_path, "settings.ini")  # Path to the settings file
        self.standalone_mode_enabled = False  # Default to standalone mode being disabled
        self.selected_display = "Good Display 3.7-inch"  # Default selected display
        self.selected_resolution = (240, 416)  # Default resolution for 3.7-inch display
        self.selected_orientation = "Portrait"  # Default orientation
        self.selected_save_folder = None  # Store the selected folder for saving CSV files
        self.detected_tag = None  # Initialize the detected_tag attribute

    dialog = None  # Store the dialog instance

    def send_csv_bitmap_via_nfc(self, intent):
        # Step 1: Validate self.current_data before generating bitmap
        required_keys = {"Target", "Range", "Elv", "Wnd1", "Wnd2", "Lead"}
        if (
            not self.current_data
            or not isinstance(self.current_data, list)
            or not all(isinstance(row, dict) and required_keys.issubset(row.keys()) for row in self.current_data)
        ):
            toast("Data is incomplete or malformed. Please reload or re-enter.")
            return

        # 1. Convert CSV to bitmap
        output_path = self.csv_to_bitmap(self.current_data)
        if not output_path:
            print("Failed to create bitmap.")
            return

        # 2. Read bitmap as 1bpp bytes and get dimensions &  Validate bitmap buffer size before sending to Java
        from PIL import Image
        with Image.open(output_path) as img:
            img = img.convert("1", dither=Image.FLOYDSTEINBERG)
            if self.selected_orientation == "Portrait":
                img = img.rotate(-90, expand=True)
            # else: do not rotate for landscape
            image_buffer = pack_image_column_major(img)
            width, height = img.size
        expected_size = width * height // 8
        if len(image_buffer) != expected_size:
            toast("Bitmap size error. Cannot send to NFC.")
            print(f"Bitmap size error: got {len(image_buffer)}, expected {expected_size}")
            return

        # 3. Prepare epd_init (replace with your actual values)
        epd_init = self.EPD_INIT_MAP.get(self.selected_display)
        if not epd_init:
            print(f"No epd_init found for display: {self.selected_display}")
            return
        print("epd_init[0] raw string:", repr(epd_init[0]))
        print("epd_init[0] hex length:", len(epd_init[0]))
        try:
            test_bytes = bytes.fromhex(epd_init[0])
            print("epd_init[0] bytes length:", len(test_bytes))
        except Exception as e:
            print("Error converting epd_init[0] to bytes:", e)

        print(f"epd_init[0]: {epd_init[0]}")
        print(f"epd_init[0] length: {len(bytes.fromhex(epd_init[0]))} bytes")
        epd_init_bytes = bytes.fromhex(epd_init[0])
        print("epd_init[0] bytes:", epd_init_bytes)
        print("epd_init[0] length (bytes):", len(epd_init_bytes))
        print("First 16 bytes of image_buffer:", list(image_buffer[:16]))
        print("Image buffer length:", len(image_buffer))

        # 4. Final validation before sending to Java
        # Check for all-zero (blank) image buffer
        if all(b == 0 for b in image_buffer):
            print("ERROR: Image buffer is all zeros (blank image)!")
            toast("Cannot send blank image to NFC tag.")
            return

        # 4. Pass the intent down!
        print("epd_init[0] right before Java:", repr(epd_init[0]), len(epd_init[0]))
        self.send_nfc_image(intent, width, height, image_buffer, epd_init)

    def send_nfc_image(self, intent, width, height, image_buffer, epd_init):
        print("send_nfc_image called")
        print(f"image_buffer type: {type(image_buffer)}")
        print("image_buffer length:", len(image_buffer))
        print("epd_init type:", type(epd_init))
        print("epd_init contents:", epd_init)
        expected_size = width * height // 8
        if len(image_buffer) != expected_size:
            print(f"WARNING: Image buffer size ({len(image_buffer)}) does not match expected size ({expected_size}) for {width}x{height} display.")
            toast("Critical error: Image buffer size mismatch.")
            return
        NfcHelper = autoclass('com.openedope.open_edope.NfcHelper')
        ByteBuffer = autoclass('java.nio.ByteBuffer')
        image_buffer_bb = ByteBuffer.wrap(image_buffer)

        # Convert epd_init to Java String[]
        String = autoclass('java.lang.String')
        Array = autoclass('java.lang.reflect.Array')
        epd_init_java_array = Array.newInstance(String, len(epd_init))
        for i, s in enumerate(epd_init):
            epd_init_java_array[i] = String(s)
        # Create the progress listener
        listener = NfcProgressListener(self)
        # Call the ByteBuffer method
        NfcHelper.processNfcIntentByteBufferAsync(intent, width, height, image_buffer_bb, epd_init_java_array, listener)
    def on_pause(self):
        print("on_pause CALLED")
        return True  # Returning True allows the app to be paused
# ...inside your MainApp class...

    def on_start(self):
        # Bind global key handler for Tab/Enter navigation
        from kivy.core.window import Window
        Window.bind(on_key_down=self.global_key_handler)

    def global_key_handler(self, window, key, scancode, codepoint, modifiers):
        # Only act if HomeScreen is current
        if self.root.ids.screen_manager.current != "home":
            return False
        # Only act if a text field is focused
        focused = [w for w in self.get_all_homepage_fields() if getattr(w, 'focus', False)]
        if not focused:
            return False
        idx = self.get_all_homepage_fields().index(focused[0])
        all_fields = self.get_all_homepage_fields()
        # Tab or Enter
        if key in (9, 13):
            if idx + 1 < len(all_fields):
                all_fields[idx + 1].focus = True
                return True  # Only block Tab/Enter
        return False  # Allow all other keys (including Backspace)

    def get_all_homepage_fields(self):
        # Collect all homepage input fields in navigation order
        home_screen = self.root.ids.home_screen
        input_ids = [
            "stage_name_field",
            # Add more static fields here if needed, BEFORE manual data
        ]
        all_fields = []
        for field_id in input_ids:
            if hasattr(home_screen.ids, field_id):
                all_fields.append(home_screen.ids[field_id])
        # Insert manual data fields after stage name
        if hasattr(self, "manual_data_fields"):
            all_fields.extend(self.manual_data_fields)
        # Add stage notes last
        if hasattr(home_screen.ids, "stage_notes_field"):
            all_fields.append(home_screen.ids.stage_notes_field)
        return all_fields


    def on_resume(self):
        print("on_resume CALLED")
        self.enable_nfc_foreground_dispatch()
        PythonActivity = autoclass('org.kivy.android.PythonActivity')
        intent = PythonActivity.mActivity.getIntent()
        action = intent.getAction()
        print(f"Checking for new intent on resume... Action: {action}")

        # Check for SEND, VIEW, or any NFC action
        if action in [
            "android.intent.action.SEND",
            "android.intent.action.VIEW",
            "android.nfc.action.TAG_DISCOVERED",
            "android.nfc.action.NDEF_DISCOVERED",
            "android.nfc.action.TECH_DISCOVERED",
        ]:
            print("Calling on_new_intent from on_resume")
            Clock.schedule_once(lambda dt: self.on_new_intent(intent), 0)
            # Action is now cleared within on_new_intent after processing
        else:
            print("No shared file/text or NFC intent to process on resume.")

    def request_bal_exemption(self):
        if is_android() and autoclass:
            try:
                ActivityCompat = autoclass('androidx.core.app.ActivityCompat')
                PythonActivity = autoclass('org.kivy.android.PythonActivity')
                activity = PythonActivity.mActivity

                # Request BAL exemption
                ActivityCompat.requestPermissions(
                    activity,
                    ["android.permission.BAL_EXEMPTION"],
                    0
                )
                print("Requested BAL exemption.")
            except Exception as e:
                print(f"Error requesting BAL exemption: {e}")
    def delete_old_folders(self):
        """Delete folders in assets/CSV older than the selected threshold."""
        thresholds = {
            "week": 7 * 24 * 3600,
            "month": 30 * 24 * 3600,
            "year": 365 * 24 * 3600,
            "never": None,
        }
        option = getattr(self, "delete_folders_after", "never").lower()
        threshold = thresholds.get(option)
        if threshold is None:
            return  # Never delete

        csv_dir = self.ensure_csv_directory()
        now = time.time()
        for folder in os.listdir(csv_dir):
            folder_path = os.path.join(csv_dir, folder)
            if os.path.isdir(folder_path):
                mtime = os.path.getmtime(folder_path)
                if now - mtime > threshold:
                    try:
                        shutil.rmtree(folder_path)
                        print(f"Deleted old folder: {folder_path}")
                    except Exception as e:
                        print(f"Error deleting folder {folder_path}: {e}")
    def build(self):

        """Build the app's UI and initialize settings."""
        # Set the theme to Light
        self.theme_cls.theme_style = "Light"

        # Load saved settings
        self.load_settings()

        # Request permissions on Android
        if is_android():
            request_permissions([
                Permission.NFC,
                Permission.READ_EXTERNAL_STORAGE,
                Permission.WRITE_EXTERNAL_STORAGE,
                Permission.VIBRATE,  # <-- Add this line
            ], self.on_permissions_result)
            if self.initialize_nfc():
                print("NFC initialized successfully.")
            from android import activity
            activity.bind(on_new_intent=self.on_new_intent)

        # Dynamically set the rootpath for the FileChooserListView
        self.root = Builder.load_file("layout.kv")  # Load the root widget from the KV file
        saved_cards_screen = self.root.ids.screen_manager.get_screen("saved_cards")
        csv_directory = self.ensure_csv_directory()

        # Handle the intent if the app was opened via an intent
        if is_android():
            try:
                PythonActivity = autoclass('org.kivy.android.PythonActivity')
                intent = PythonActivity.mActivity.getIntent()
                print(f"Scheduling on_new_intent for action: {intent.getAction()}")  # <-- Add this line
                Clock.schedule_once(lambda dt: self.on_new_intent(intent), 0)
            except Exception as e:
                print(f"Error handling startup intent: {e}")
            def poll_intent(dt):
                PythonActivity = autoclass('org.kivy.android.PythonActivity')
                intent = PythonActivity.mActivity.getIntent()
                action = intent.getAction()
                if action in [
                    "android.nfc.action.TAG_DISCOVERED",
                    "android.nfc.action.NDEF_DISCOVERED",
                    "android.nfc.action.TECH_DISCOVERED",
                ]:
                    print(f"Polling: found NFC intent action: {action}")
                    self.on_new_intent(intent)
                    intent.setAction("")  # Clear so it doesn't get handled again
        
            # Poll every second for new NFC intents
            Clock.schedule_interval(poll_intent, 1)
        
        # Initialize the dropdown menus
        self.display_menu = None
        self.orientation_menu = None

        # Set the default text for the display and orientation dropdown buttons
        self.root.ids.settings_screen.ids.display_dropdown_button.text = self.selected_display
        self.root.ids.settings_screen.ids.orientation_dropdown_button.text = self.selected_orientation

        # Hide the NFC button if on Android
        self.hide_nfc_button()

        # Delay check for empty table and show manual input if needed
        def check_and_show_manual_input(dt):
            # Only show manual input if there is no data loaded
            if not hasattr(self, "current_data") or not self.current_data:
                print("No data found after UI load, showing manual data input.")
                self.show_manual_data_input()

        Clock.schedule_once(check_and_show_manual_input, 0.5)  # Delay to ensure UI is loaded

        return self.root

    def clear_table_data(self):
        """Clear the data in the table and update the UI."""
        self.current_data = []
        if hasattr(self, "manual_data_rows"):
            self.manual_data_rows = []
        self.manual_data_fields = []  # <-- Add this line
        home_screen = self.root.ids.home_screen
        table_container = home_screen.ids.table_container
        table_container.clear_widgets()
        # Clear the stage name and stage notes fields
        try:
            home_screen.ids.stage_name_field.text = ""
            home_screen.ids.stage_notes_field.text = ""
            print("Stage name and stage notes fields cleared.")
        except Exception as e:
            print(f"Error clearing stage name or notes: {e}")
        print("Data table cleared.")
        self.show_manual_data_input()  # Show manual data input fields again

    global show_lead, show_range, show_2_wind_holds
    show_lead = False
    show_range = False
    show_2_wind_holds = True
        
    def ensure_csv_directory(self):
        """Ensure the assets/CSV directory exists and is accessible."""
        if is_android():
            # Copy assets/CSV to internal storage on Android
            return self.copy_assets_to_internal_storage()
        else:
            # Use the local assets/CSV folder on non-Android platforms
            csv_directory = os.path.join(os.path.dirname(__file__), "assets", "CSV")
            if not os.path.exists(csv_directory):
                os.makedirs(csv_directory)
            return csv_directory

    def on_file_selected(self, selection):
        """Handle the file or folder selected in the FileChooserListView."""
         # Clear current data and table before loading new file
        self.current_data = []
        self.clear_table_data()
        if selection:
            selected_path = selection[0]
            if os.path.isdir(selected_path):
                # If it's a folder, show its contents
                self.populate_swipe_file_list(selected_path)
                return
            # Extract the file name and set it to the stage_name_field
            file_name = os.path.basename(selected_path)
            self.root.ids.home_screen.ids.stage_name_field.text = os.path.splitext(file_name)[0]
            # If the selected file is a CSV, extract the stage notes footer and display it in the stage_notes_field
            if selected_path.endswith(".csv"):
                try:
                    with open(selected_path, mode="r", encoding="utf-8") as csv_file:
                        lines = csv_file.readlines()
                        # Look for the "Stage Notes:" footer and extract the notes
                        for i, line in enumerate(lines):
                            if line.strip().lower() == "stage notes:":
                                stage_notes = "".join(lines[i + 1:]).strip()
                                self.root.ids.home_screen.ids.stage_notes_field.text = stage_notes
                                break
                except Exception as e:
                    print(f"Error extracting stage notes: {e}")
            print(f"Selected: {selected_path}")  # Log the selected file or folder

            # Check if the selected file is a CSV
            if selected_path.endswith(".csv"):
                try:
                    # Read the CSV file and convert it to a dictionary
                    data = self.read_csv_to_dict(selected_path)
                    self.current_data = data  # Store the data for filtering or other operations

                    # Preprocess the data
                    processed_data = self.preprocess_data(data)

                    # Display the data as a table on the Home Screen
                    self.display_table(processed_data)

                    # Reset the FileChooserListView to its rootpath
                    saved_cards_screen = self.root.ids.screen_manager.get_screen("saved_cards")

                    # Navigate back to the Home Screen
                    self.root.ids.screen_manager.current = "home"  # Reference the Home Screen by its name in layout.kv

                    print(f"CSV loaded: {os.path.basename(selected_path)}")
                except Exception as e:
                    print(f"Error reading CSV: {e}")
            else:
                print("Please select a valid CSV file.")
        else:
            print("No file selected")

    def read_csv_to_dict(self, file_or_path):
        """Reads a CSV file or file-like object and maps it to static column names, ignoring the headers and skipping the first 6 lines."""
        static_columns = ["Target", "Range", "Elv", "Wnd1", "Wnd2", "Lead"]  # Static column names
        data = []
        try:
            print(f"Reading CSV: {file_or_path}")
            # Detect if file_or_path is a path or file-like object
            if isinstance(file_or_path, str):
                csv_file = open(file_or_path, mode="r", encoding="latin-1")
                close_after = True
            else:
                csv_file = file_or_path
                close_after = False

            reader = csv.reader(csv_file)
            # Skip the first 6 lines
            for _ in range(6):
                next(reader, None)
            for index, row in enumerate(reader, start=1):
                if not row:
                    continue
                if row[0].strip().lower() == "stage notes:":
                    break
                mapped_row = {static_columns[i]: row[i] if i < len(row) else "" for i in range(len(static_columns))}
                data.append(mapped_row)
            if close_after:
                csv_file.close()
            print(f"CSV data read successfully: {data}")
        except Exception as e:
            print(f"Error reading CSV file: {e}")
        return data

    def preprocess_data(self, data):
        """Shift columns to the right by one if 'Target' contains a number."""
        processed_data = []
        for row in data:
            target_value = row.get("Target", "")
            # Check if the "Target" column contains a number
            try:
                float(target_value)
                is_number = float(target_value) > 40
            except (ValueError, TypeError):
                is_number = False

            if is_number:
                # Shift the columns across to the right by one
                shifted_row = {}
                keys = list(row.keys())
                for i in range(len(keys) - 1):
                    shifted_row[keys[i + 1]] = row[keys[i]]
                shifted_row[keys[0]] = ""  # Set the first column to empty
                processed_data.append(shifted_row)
            else:
                # Keep the row as is if "Target" is not a number
                processed_data.append(row)
        return processed_data

    def display_table(self, data):
        global show_range
        # Check if data is empty
        if not data:
            print("No data to display.")
            return

        # Preprocess the data to handle numeric "Target" values
        data = self.preprocess_data(data)

        # --- Filter out rows where all values after "Target" are "---" ---
        if data:
            header = data[0]
            filtered_data = [header]
            for row in data[1:]:
                values_after_target = [v for k, v in row.items() if k != "Target"]
                if not all(str(v).strip() == "---" for v in values_after_target):
                    filtered_data.append(row)
            data = filtered_data

        # --- Filter out rows where all values are "0" ---
        if data:
            header = data[0]
            filtered_data_zeros = [header]
            for row in data[1:]:
                # Check if all values in the row are "0" (as strings)
                if not all(str(v).strip() == "0" for v in row.values()):
                    filtered_data_zeros.append(row)
            data = filtered_data_zeros

    # Check if Range is the first column in the data
        if data and list(data[0].keys())[0] == "Range":
            show_range = True
            print("Range is in column 0, setting show_range = True")

        # --- Use the exact header and row logic as display_table ---
        static_headers = ["Target", "Range", "Elv", "Wnd1", "Wnd2", "Lead"]
        headers = ["Elv", "Wnd1"]
        target_present = any(row.get("Target") for row in data)
        if target_present:
            headers.insert(0, "Target")
        if show_range:
            if not target_present:
                headers.insert(0, "Range")
            else:
                headers.insert(1, "Range")
        if show_2_wind_holds:
            headers.append("Wnd2")
        if show_lead:
            headers.append("Lead")

        # Filter the data rows based on the selected headers
        filtered_data = [
            {header: row.get(header, "") for header in headers} for row in data
        ]

        # Calculate the maximum width for each column, using the displayed header text
        column_widths = {}
        for header in headers:
            display_header = "Tgt" if header == "Target" else "Rng" if header == "Range" else header
            column_widths[header] = len(display_header)

        for row in filtered_data:
            for header in headers:
                column_widths[header] = max(column_widths[header], len(str(row.get(header, ""))))

        # Format the headers and rows as text
        table_text = " | ".join(f"{header:<{column_widths[header]}}" for header in headers) + "\n"  # Add headers
        table_text += "-" * (sum(column_widths.values()) + len(headers) * 3 - 1) + "\n"  # Add a separator line
        for row in filtered_data:
            table_text += " | ".join(
                f"{str(row.get(header, '')):<{column_widths[header]}}" for header in headers) + "\n"  # Add rows

        # Add the text to the table_container in Home Screen
        home_screen = self.root.ids.home_screen
        table_container = home_screen.ids.table_container
        table_container.clear_widgets()  # Clear any existing widgets in the container

        # Create a Label to display the table text
        table_label = Label(
            text=table_text,
            halign="center",
            valign="center",
            size_hint=(1, 1),
            text_size=(table_container.width, None),
            color=(0, 0, 0, 1),  # Set text color to black
            font_name="assets/fonts/RobotoMono-Regular.ttf",  # Path to the font file
        )
        table_container.add_widget(table_label)

    def on_dots_press(self, instance):
        global show_lead, show_range, show_2_wind_holds

        # Dismiss the existing menu if it exists
        if hasattr(self, "menu") and self.menu:
            self.menu.dismiss()

        # Update the "Show Lead" menu item dynamically
        if show_lead:
            lead_menu = {"text": "Hide Lead",
                         "on_release": lambda: (self.menu_callback("Hide Lead"), self.menu.dismiss())}
        else:
            lead_menu = {"text": "Show Lead",
                         "on_release": lambda: (self.menu_callback("Show Lead"), self.menu.dismiss())}
        # Update the "Show Range" menu item dynamically
        if show_range:
            range_menu = {"text": "Hide Range",
                          "on_release": lambda: (self.menu_callback("Hide Range"), self.menu.dismiss())}
        else:
            range_menu = {"text": "Show Range",
                          "on_release": lambda: (self.menu_callback("Show Range"), self.menu.dismiss())}
        # Update the "Show 2 Wind Holds" menu item dynamically
        if show_2_wind_holds:
            wind_holds_menu = {"text": "Show 1 Wind Hold",
                               "on_release": lambda: (self.menu_callback("Show 1 Wind Hold"), self.menu.dismiss())}
        else:
            wind_holds_menu = {"text": "Show 2 Wind Holds",
                               "on_release": lambda: (self.menu_callback("Show 2 Wind Holds"), self.menu.dismiss())}

        # Define menu items
        menu_items = [
            {"text": "Settings", "on_release": lambda: self.menu_callback("Settings")},
            lead_menu,
            range_menu,
            wind_holds_menu,
        ]

        # Create the dropdown menu
        self.menu = MDDropdownMenu(
            caller=instance,
            items=menu_items,
        )
        self.menu.open()

    def menu_callback(self, option):
        global show_lead, show_range, show_2_wind_holds

        # Handle the selected option
        if option == "Hide Lead":
            show_lead = False
        elif option == "Show Lead":
            show_lead = True
        if option == "Hide Range":
            show_range = False
        elif option == "Show Range":
            show_range = True
        if option == "Show 1 Wind Hold":
            show_2_wind_holds = False
        elif option == "Show 2 Wind Holds":
            show_2_wind_holds = True
        elif option == "Settings":
            # Navigate to the settings screen
            self.root.ids.screen_manager.current = "settings"

            # Close the dots menu
            if hasattr(self, "menu") and self.menu:
                self.menu.dismiss()

        # Regenerate the manual data input fields if they are visible
        home_screen = self.root.ids.home_screen
        table_container = home_screen.ids.table_container
        if table_container.children:  # Check if manual data input fields are displayed
            self.show_manual_data_input()

        # Regenerate the table with updated columns
        if hasattr(self, "current_data"):  # Check if data is already loaded
            filtered_data = self.filter_table_data(self.current_data)
            self.display_table(filtered_data)

    def filter_table_data(self, data):
        """Filters the table data based on the show_lead and show_2_wind_holds flags."""
        filtered_data = []
        for row in data:
            filtered_row = {}
            # Add columns in the static order
            filtered_row["Target"] = row.get("Target", "")
            if show_range:
                filtered_row["Range"] = row.get("Range", "")
            filtered_row["Elv"] = row.get("Elv", "")
            filtered_row["Wnd1"] = row.get("Wnd1", "")
            if show_2_wind_holds:
                filtered_row["Wnd2"] = row.get("Wnd2", "")
            if show_lead:
                filtered_row["Lead"] = row.get("Lead", "")
            filtered_data.append(filtered_row)
        return filtered_data

    def on_fab_press(self):
        """Handle the floating action button press."""
        # Get the stage name from the text field
        stage_name = self.root.ids.home_screen.ids.stage_name_field.text.strip()
        if not stage_name:
            toast("Stage Name required for Save")
            return  # Do not open the save dialog

        if not self.dialog:
            # Get the list of folders in the assets/CSV directory
            csv_directory = self.ensure_csv_directory()
            folders = [f for f in os.listdir(csv_directory) if os.path.isdir(os.path.join(csv_directory, f))]

            # Create a BoxLayout to hold the dropdown button and text input
            content_layout = BoxLayout(
                orientation="vertical",
                spacing="10dp",  # Add spacing between the button and text field
                size_hint=(1, None),
                height="120dp",  # Adjust height to fit both widgets
            )

            # Add the dropdown button to the layout
            dropdown_button = MDFlatButton(
                id="dropdown_button",
                text="Select Event",
                size_hint=(1, None),
                height="48dp",
                pos_hint={"center_x": 0.5},
            )

            # Define the function to handle menu item selection
            def update_selected_folder(selected_option):
                dropdown_button.text = selected_option  # Update the button text to display the selected option
                dropdown_menu.dismiss()  # Close the dropdown menu
                if selected_option == "New Event...":
                    text_input.opacity = 1  # Make the text input visible
                    text_input.disabled = False  # Enable the text input
                    self.selected_save_folder = None  # Clear the selected folder
                else:
                    text_input.opacity = 0  # Hide the text input
                    text_input.disabled = True  # Disable it
                    self.selected_save_folder = os.path.join(csv_directory, selected_option)

            # Create the dropdown menu
            dropdown_menu = MDDropdownMenu(
                caller=dropdown_button,
                items=[{"text": "New Event...", "on_release": lambda: update_selected_folder("New Event...")}] +
                      [{"text": folder,
                        "on_release": lambda selected_folder=folder: update_selected_folder(selected_folder)}
                       for folder in folders],
                position="center",
            )

            # Assign the menu to the button's on_release callback
            dropdown_button.on_release = lambda: dropdown_menu.open()

            # Add the text input field to the layout, initially hidden
            text_input = MDTextField(
                hint_text="Event Name",
                size_hint=(1, None),
                height="48dp",
                multiline=False,
                opacity=0,  # Make it invisible initially
                disabled=True,  # Disable it initially
                halign="center",  # Center the text horizontally
            )

            # Add both widgets to the layout
            content_layout.add_widget(dropdown_button)
            content_layout.add_widget(text_input)

            # Add the layout to the dialog
            self.dialog = MDDialog(
                title="Save Data",
                text="Select an event folder or create a new one.",
                type="custom",
                content_cls=content_layout,
                buttons=[
                    MDFlatButton(
                        text="CANCEL",
                        on_release=lambda x: self.dialog.dismiss()
                    ),
                    MDFlatButton(
                        text="SAVE",
                        on_release=lambda x: (
                            self.handle_save_dialog(text_input),
                            self.dialog.dismiss()  # Automatically close the dialog after saving
                        ),
                        theme_text_color="Custom",          # Make the text color custom
                        text_color=(0, 0.4, 1, 1)           # Blue color for SAVE button
                    ),
                ],
            )

        self.dialog.open()
    def handle_save_dialog(self, text_input):
        home_screen = self.root.ids.home_screen
        table_container = home_screen.ids.table_container
    
        # Add manual data if any manual fields are filled
        if (
            table_container.children
            and hasattr(self, "manual_data_rows")
            and self.manual_data_rows
            and any(
                any(field.text.strip() for field in row_fields.values())
                for row_fields in self.manual_data_rows
            )
        ):
            print("Manual data input detected, adding manual data before saving.")
            self.add_manual_data()
    
        self.save_data(new_event_name=text_input.text.strip() if text_input.text.strip() else None)
        self.dialog.dismiss()
    
    def save_data(self, new_event_name=None):
        if hasattr(self, "current_data") and self.current_data:
            # Filter out rows where all values after "Target" are "---"
            header = self.current_data[0]
            filtered_data = [header]
            for row in self.current_data[1:]:
                values_after_target = [v for k, v in row.items() if k != "Target"]
                if not all(str(v).strip() == "---" for v in values_after_target):
                    filtered_data.append(row)
            self.current_data = filtered_data
            # Determine the private storage path
            storage_path = self.get_private_storage_path()
            if storage_path:
                try:
                    # Ensure the CSV folder exists
                    csv_folder_path = os.path.join(storage_path, "CSV")
                    if not os.path.exists(csv_folder_path):
                        os.makedirs(csv_folder_path)

                    # Construct the file name and path
                    file_name = f"{self.root.ids.home_screen.ids.stage_name_field.text}.csv"
                    if new_event_name:
                        # Use the new event name to create a folder inside the CSV folder
                        event_folder_path = os.path.join(csv_folder_path, new_event_name)
                        if not os.path.exists(event_folder_path):
                            os.makedirs(event_folder_path)  # Create the folder if it doesn't exist
                        file_path = os.path.join(event_folder_path, file_name)
                    elif self.selected_save_folder:
                        # Use the selected folder inside the CSV folder
                        if not os.path.exists(self.selected_save_folder):
                            os.makedirs(self.selected_save_folder)  # Create the folder if it doesn't exist
                        file_path = os.path.join(self.selected_save_folder, file_name)
                    else:
                        toast("No folder selected or created. Cannot save data.")
                        return

                    # Write the data to the CSV file
                    with open(file_path, mode="w", encoding="utf-8", newline="") as csv_file:
                        writer = csv.writer(csv_file)

                        # Add 6 empty rows as the header
                        for _ in range(5):
                            writer.writerow([])

                        # Write the headers
                        headers = self.current_data[0].keys()
                        writer.writerow(headers)

                        # Write the data rows
                        for row in self.current_data:
                            writer.writerow(row.values())

                        # Write the stage notes as the footer
                        stage_notes = self.root.ids.home_screen.ids.stage_notes_field.text.strip()
                        if stage_notes:
                            writer.writerow([])  # Add an empty row before the footer
                            writer.writerow(["Stage Notes:"])
                            writer.writerow([stage_notes])

                        print(f"Data saved to: {file_path}")
                        toast(f"Data saved")

                        # Refresh the FileChooserListView
                        saved_cards_screen = self.root.ids.screen_manager.get_screen("saved_cards")
                        filechooser.path = filechooser.path  # Refresh the file and folder list
                        print("File and folder list refreshed.")
                except Exception as e:
                    print(f"Error saving data to CSV: {e}")
            else:
                print("Private storage path is not available.")
        else:
            print("No data available to save.")

    def save_settings(self):
        """Save the selected settings to a configuration file."""
        try:
            # Add a section for settings if it doesn't exist
            if not self.config_parser.has_section("Settings"):
                self.config_parser.add_section("Settings")
            self.config_parser.set("Settings", "display_model", self.selected_display)
            self.config_parser.set("Settings", "orientation", self.selected_orientation)
            self.config_parser.set("Settings", "standalone_mode", str(self.standalone_mode_enabled))
            # Save show/hide preferences
            self.config_parser.set("Settings", "show_lead", str(show_lead))
            self.config_parser.set("Settings", "show_range", str(show_range))
            self.config_parser.set("Settings", "show_2_wind_holds", str(show_2_wind_holds))
            # Save sort settings
            self.config_parser.set("Settings", "sort_type", getattr(self, "sort_type", "date"))
            self.config_parser.set("Settings", "sort_order", getattr(self, "sort_order", "asc"))
            self.config_parser.set("Settings", "delete_folders_after", getattr(self, "delete_folders_after", "never"))
            self.config_parser.set("Settings", "manage_data_dialog_shown", str(getattr(self, "manage_data_dialog_shown", False)))
            with open(self.config_file, "w") as config_file:
                self.config_parser.write(config_file)
            print("Settings saved successfully.")
        except Exception as e:
            print(f"Error saving settings: {e}")

    def load_settings(self):
        global show_lead, show_range, show_2_wind_holds
        try:
            self.config_parser.read(self.config_file)
            if self.config_parser.has_option("Settings", "display_model"):
                self.selected_display = self.config_parser.get("Settings", "display_model")
            if self.config_parser.has_option("Settings", "orientation"):
                self.selected_orientation = self.config_parser.get("Settings", "orientation")
            # Load show/hide preferences
            if self.config_parser.has_option("Settings", "show_lead"):
                show_lead = self.config_parser.getboolean("Settings", "show_lead")
            if self.config_parser.has_option("Settings", "show_range"):
                show_range = self.config_parser.getboolean("Settings", "show_range")
            if self.config_parser.has_option("Settings", "show_2_wind_holds"):
                show_2_wind_holds = self.config_parser.getboolean("Settings", "show_2_wind_holds")
            # Set native_resolution and selected_resolution based on loaded display/orientation
            display_resolutions = {
                "Good Display 3.7-inch": (240, 416),
                "Good Display 4.2-inch": (300, 400),
                "Good Display 2.9-inch": (128, 296),
            }
            self.native_resolution = display_resolutions.get(self.selected_display, (240, 416))
            self.selected_resolution = self.native_resolution  # Always portrait
            print(f"Loaded settings: display_model={self.selected_display}, orientation={self.selected_orientation}, ...")
            print(f"Loaded native_resolution: {self.native_resolution}, selected_resolution: {self.selected_resolution}")
        except Exception as e:
            print(f"Error loading settings: {e}")
        self.delete_folders_after = self.config_parser.get("Settings", "delete_folders_after", fallback="never")
        labels = {
            "week": "After 1 week",
            "month": "After 1 month",
            "year": "After 1 year",
            "never": "Never",
        }
        self.delete_option_label = labels.get(self.delete_folders_after, "Delete Folders After")
        # Load sort settings
        self.sort_type = self.config_parser.get("Settings", "sort_type", fallback="date")
        self.sort_order = self.config_parser.get("Settings", "sort_order", fallback="asc")
        print(f"Loaded sort_type: {self.sort_type}, sort_order: {self.sort_order}")

    def csv_to_bitmap(self, csv_data, output_path=None):
        """Convert CSV data to a bitmap image, resize it to fit the display resolution while keeping the aspect ratio, and save it."""
        try:
            # Set the default output path to the assets/bitmap folder
            bitmap_directory = os.path.join(os.path.dirname(__file__), "assets", "bitmap")
            if not os.path.exists(bitmap_directory):
                os.makedirs(bitmap_directory)  # Create the directory if it doesn't exist

            # Use the default output path if none is provided
            if output_path is None:
                output_path = os.path.join(bitmap_directory, "output.bmp")

            # Default resolution if no display is selected
            display_width, display_height = 240, 416

           
            # Load the font file (ensure the font file is in the correct path)
            font_path = os.path.join(os.path.dirname(__file__), "assets", "fonts", "RobotoMono-Regular.ttf")
            font = ImageFont.truetype(font_path, 12)  # Load the font file

            # 1. Always draw at portrait base size
            base_width, base_height = 240, 416
            image = Image.new("RGB", (base_width, base_height), "white")
            draw = ImageDraw.Draw(image)

            # Add the stage name at the top
            stage_name = self.root.ids.home_screen.ids.stage_name_field.text  # Get the stage name from the text field
            y = 10  # Starting vertical position
            text_bbox = draw.textbbox((0, 0), stage_name, font=font)  # Get the bounding box of the text
            text_width = text_bbox[2] - text_bbox[0]  # Calculate the text width
            x = (base_width - text_width) // 2  # Center the text horizontally
            draw.text((x, y), stage_name, fill="black", font=font)
            y += 20  # Add some spacing after the stage name

            # Draw a horizontal line under the stage name
            draw.line((10, y, base_width - 10, y), fill="black", width=1)
            y += 20  # Add some spacing after the line

            # --- Use the exact header and row logic as display_table ---
            processed_data = self.preprocess_data(csv_data)

            # Filter out rows where all values after "Target" are "---"
            if processed_data:
                header = processed_data[0]
                filtered_data = [header]
                for row in processed_data[1:]:
                    values_after_target = [v for k, v in row.items() if k != "Target"]
                    if not all(str(v).strip() == "---" for v in values_after_target):
                        filtered_data.append(row)
                processed_data = filtered_data
            # Filter out rows where all values are "0"
            if processed_data:
                header = processed_data[0]
                filtered_data_zeros = [header]
                for row in processed_data[1:]:
                    # Check if all values in the row are "0" (as strings)
                    if not all(str(v).strip() == "0" for v in row.values()):
                        filtered_data_zeros.append(row)
                processed_data = filtered_data_zeros

            static_headers = ["Target", "Range", "Elv", "Wnd1", "Wnd2", "Lead"]
            headers = ["Elv", "Wnd1"]
            target_present = any(row.get("Target") for row in processed_data)
            if target_present:
                headers.insert(0, "Target")
            if show_range:
                if not target_present:
                    headers.insert(0, "Range")
                else:
                    headers.insert(1, "Range")
            if show_2_wind_holds:
                headers.append("Wnd2")
            if show_lead:
                headers.append("Lead")

            filtered_data = [
                {header: row.get(header, "") for header in headers} for row in processed_data
            ]

            # Calculate the maximum width for each column, using the displayed header text
            column_widths = {}
            for header in headers:
                display_header = "Tgt" if header == "Target" else "Rng" if header == "Range" else header
                column_widths[header] = len(display_header)

            for row in filtered_data:
                for header in headers:
                    column_widths[header] = max(column_widths[header], len(str(row.get(header, ""))))

            # Write headers to the image
            headers_text = " | ".join(
                f"{('Tgt' if header == 'Target' else 'Rng' if header == 'Range' else header):<{column_widths[header]}}"
                for header in headers
            )
            text_bbox = draw.textbbox((0, 0), headers_text, font=font)
            text_width = text_bbox[2] - text_bbox[0]
            x = (base_width - text_width) // 2
            draw.text((x, y), headers_text, fill="black", font=font)
            y += 20

            # Write CSV data to the image
            for row in filtered_data:
                row_text = " | ".join(f"{str(row.get(header, '')):<{column_widths[header]}}" for header in headers)
                text_bbox = draw.textbbox((0, 0), row_text, font=font)
                text_width = text_bbox[2] - text_bbox[0]
                x = (base_width - text_width) // 2
                draw.text((x, y), row_text, fill="black", font=font)
                y += 20

            # Add the stage notes below the table data
            stage_notes = self.root.ids.home_screen.ids.stage_notes_field.text  # Get the stage notes from the text field
            y += 20  # Add some spacing before the stage notes
            draw.line((10, y, base_width - 10, y), fill="black", width=1)  # Draw a line above the stage notes
            y += 10  # Add some spacing after the line
            text_bbox = draw.textbbox((0, 0), "Stage Notes:", font=font)  # Get the bounding box of the stage notes label
            text_width = text_bbox[2] - text_bbox[0]  # Calculate the text width
            x = (base_width - text_width) // 2  # Center the text horizontally
            draw.text((x, y), "Stage Notes:", fill="black", font=font)
            y += 30  # Add some spacing after the stage notes label
            draw.line((10, y, base_width - 10, y), fill="black", width=1)  # Draw a horizontal line under the stage notes label
            y += 20  # Add some spacing after the line
            text_bbox = draw.textbbox((0, 0), stage_notes, font=font)  # Get the bounding box of the stage notes
            text_width = text_bbox[2] - text_bbox[0]  # Calculate the text width
            x = (base_width - text_width) // 2  # Center the text horizontally
            draw.text((x, y), stage_notes, fill="black", font=font)

            # 2. Determine the final output size based on orientation and selected display
            portrait_resolution = self.selected_resolution  # e.g., (240, 416) for 3.7"
            if self.selected_orientation == "Landscape":
                final_resolution = (portrait_resolution[1], portrait_resolution[0])  # e.g., (416, 240)
            else:
                final_resolution = portrait_resolution  # e.g., (240, 416)

            # 3. Resize to the final resolution (this will stretch/squash if needed)
            image = image.resize(final_resolution, Image.LANCZOS)

            # Save the resized image as a bitmap
            bw_image = image.convert("1")  # Convert to 1-bit pixels
            bw_image.save(output_path)
            print(f"Bitmap saved to {output_path}")
            print(f"Bitmap dimensions: {bw_image.size}")
            return output_path
        except Exception as e:
            print(f"Error converting CSV to bitmap: {e}")
            return None
    def navigate_to_home(self):
        """Navigate back to the home screen."""
        self.root.ids.screen_manager.current = "home"

    # search functionality below
    def on_search_entered(self, search_text):
        """Filter the swipe-to-delete file list based on the search input."""
        self.search_text = search_text.strip().lower() if search_text else ""
        self.populate_swipe_file_list()

    def limit_stage_notes(self, text_field):
        """Limit the stage notes to 2 lines."""
        max_lines = 2
        lines = text_field.text.split("\n")
        if len(lines) > max_lines:
            # Trim the text to the first 2 lines
            text_field.text = "\n".join(lines[:max_lines])
            text_field.cursor = (len(text_field.text), 0)  # Reset the cursor position

    def open_display_dropdown(self, button):
        """Open the dropdown menu for selecting a display model."""
        # Define the available display models with their resolutions (always portrait)
        display_models = [
            {"text": "Good Display 3.7-inch", "resolution": (240, 416),
             "on_release": lambda: self.set_display_model("Good Display 3.7-inch", (240, 416))},
            {"text": "Good Display 4.2-inch", "resolution": (300, 400),
             "on_release": lambda: self.set_display_model("Good Display 4.2-inch", (300, 400))},
            {"text": "Good Display 2.9-inch", "resolution": (128, 296),
             "on_release": lambda: self.set_display_model("Good Display 2.9-inch", (128, 296))},
        ]

        # Create the dropdown menu if it doesn't exist
        if not self.display_menu:
            self.display_menu = MDDropdownMenu(
                caller=button,
                items=[
                    {"text": model["text"], "on_release": model["on_release"]}
                    for model in display_models
                ],
            )

        # Open the dropdown menu
        self.display_menu.open()

    def set_display_model(self, model, resolution):
        self.selected_display = model
        self.native_resolution = resolution  # Always portrait, e.g., (128, 296)
        self.selected_resolution = resolution  # Always portrait
        self.root.ids.settings_screen.ids.display_dropdown_button.text = f"{model}"
        print(f"Selected display model: {model} with native resolution {self.selected_resolution}")
        self.save_settings()
        if self.display_menu:
            self.display_menu.dismiss()

    def open_orientation_dropdown(self, button):
        """Open the dropdown menu for selecting orientation."""
        # Define the available orientations
        orientation_options = [
            {"text": "Portrait", "on_release": lambda: self.set_orientation("Portrait")},
            {"text": "Landscape", "on_release": lambda: self.set_orientation("Landscape")},
        ]

        # Create the dropdown menu if it doesn't exist
        if not hasattr(self, "orientation_menu") or not self.orientation_menu:
            self.orientation_menu = MDDropdownMenu(
                caller=button,
                items=orientation_options,
            )

        # Open the dropdown menu
        self.orientation_menu.open()

    def set_orientation(self, orientation):
        self.selected_orientation = orientation
        self.root.ids.settings_screen.ids.orientation_dropdown_button.text = orientation
        print(f"Selected orientation: {orientation}")
        # Always keep selected_resolution as portrait
        display_resolutions = {
            "Good Display 3.7-inch": (240, 416),
            "Good Display 4.2-inch": (300, 400),
            "Good Display 2.9-inch": (128, 296),
        }
        if not hasattr(self, "native_resolution") or self.native_resolution is None:
            self.native_resolution = display_resolutions.get(self.selected_display, (240, 416))
        self.selected_resolution = self.native_resolution  # Always portrait
        self.save_settings()
        if self.orientation_menu:
            self.orientation_menu.dismiss()

    def on_standalone_mode_toggle(self, active):
        """Handle the Stand Alone Mode toggle."""
        self.standalone_mode_enabled = active  # Update the standalone mode state
        print(f"Stand Alone Mode {'enabled' if active else 'disabled'}")

        # Save the updated state to the settings
        self.save_settings()

        if active:
            self.show_manual_data_input()  # Show manual data input fields
        else:
            # Clear the manual data input fields and restore the table container
            home_screen = self.root.ids.home_screen
            table_container = home_screen.ids.table_container
            table_container.clear_widgets()
            if hasattr(self, "current_data"):
                self.display_table(self.current_data)  # Restore the table if data exists

    def on_broom_button_press(self):
        """Handle the broom button press."""
        print("Broom button pressed. Performing cleanup...")
        # Add your cleanup logic here

    def get_external_storage_path(self):
        """Retrieve the external storage path using mActivity or default to assets/CSV."""
        if is_android():
            try:
                # Get the Android context
                context = mActivity.getApplicationContext()

                # Get the external files directory
                result = context.getExternalFilesDir(None)  # Pass `None` to get the root directory
                if result:
                    storage_path = str(result.toString())
                    print(f"External storage path: {storage_path}")
                    return storage_path
                else:
                    print("Failed to retrieve external storage path.")
                    return None
            except Exception as e:
                print(f"Error retrieving external storage path: {e}")
                return None
        else:
            # Default to assets/CSV folder
            csv_directory = os.path.join(os.path.dirname(__file__), "assets", "CSV")
            if not os.path.exists(csv_directory):
                os.makedirs(csv_directory)
            print(f"Defaulting to assets/CSV folder: {csv_directory}")
            return csv_directory

    def get_private_storage_path(self):
        """Retrieve the app's private storage path."""
        if is_android():
            try:
                context = mActivity.getApplicationContext()
                private_storage_path = context.getFilesDir().getAbsolutePath()
                print(f"Private storage path: {private_storage_path}")
                return private_storage_path
            except Exception as e:
                print(f"Error retrieving private storage path: {e}")
                return None
        else:
            # Use a local directory for non-Android platforms
            private_storage_path = os.path.join(os.path.dirname(__file__), "private_storage")
            if not os.path.exists(private_storage_path):
                os.makedirs(private_storage_path)
            print(f"Private storage path (non-Android): {private_storage_path}")
            return private_storage_path

    def save_to_external_storage(self, file_name, content):
        """Save a file to the external storage directory or assets/CSV."""
        storage_path = self.get_external_storage_path()
        if storage_path:
            try:
                # Construct the full file path
                file_path = os.path.join(storage_path, file_name)

                # Write the content to the file
                with open(file_path, "w", encoding="utf-8") as file:
                    file.write(content)
                print(f"File saved to: {file_path}")
            except Exception as e:
                print(f"Error saving file to storage: {e}")
        else:
            print("Storage path is not available.")

        if is_android():
            print("Running on Android. External storage is available.")
            storage_path = self.get_external_storage_path()
            if storage_path:
                print(f"External storage path: {storage_path}")
        else:
            print("Not running on Android. External storage is not available.")

    def initialize_nfc(self):
        """Initialize the NFC adapter and enable foreground dispatch."""
        if is_android() and autoclass:
            try:
                print("Initializing NFC adapter...")
                NfcAdapter = autoclass('android.nfc.NfcAdapter')
                PendingIntent = autoclass('android.app.PendingIntent')
                Intent = autoclass('android.content.Intent')
                IntentFilter = autoclass('android.content.IntentFilter')

                # Get the NFC adapter
                self.nfc_adapter = NfcAdapter.getDefaultAdapter(mActivity)
                if self.nfc_adapter is None:
                    print("NFC is not available on this device.")
                    return False

                # Create a pending intent for NFC
                intent = Intent(mActivity, mActivity.getClass())
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                print(f"Intent flags: {intent.getFlags()}")  # Log the intent flags
                self.pending_intent = PendingIntent.getActivity(
                    mActivity, 0, intent, PendingIntent.FLAG_IMMUTABLE
                )
                print(f"PendingIntent created: {self.pending_intent}")

                # Create intent filters for NFC
                self.intent_filters = [
                    IntentFilter("android.nfc.action.TAG_DISCOVERED"),
                    IntentFilter("android.nfc.action.NDEF_DISCOVERED"),
                    IntentFilter("android.nfc.action.TECH_DISCOVERED"),
                ]
                print("Intent filters created for NFC.")

                print("NFC adapter initialized successfully.")
                return True
            except Exception as e:
                print(f"Error initializing NFC: {e}")
                return False
        else:
            print("NFC functionality is only available on Android.")
            return False

    def enable_nfc_foreground_dispatch(self):
        """Enable NFC foreground dispatch to handle NFC intents."""
        if is_android() and autoclass:
            try:
                # Use the same PendingIntent and intent filters as in initialize_nfc
                self.nfc_adapter.enableForegroundDispatch(
                    mActivity,
                    self.pending_intent,
                    self.intent_filters,
                    None
                )
                print("NFC foreground dispatch enabled.")
            except Exception as e:
                print(f"Error enabling NFC foreground dispatch: {e}")

    def on_new_intent(self, intent):
        print("on_new_intent called")
        """Handle new intents, including shared data and NFC tags."""
        if is_android() and autoclass:
            try:
                # Get the action from the intent
                action = intent.getAction()
                print(f"Intent action: {action}")

                # --- Print all extras for debugging ---
                extras = intent.getExtras()
                if extras:
                    print("Intent extras:")
                    for key in extras.keySet():
                        value = extras.get(key)
                        print(f"  {key}: {value}")
                else:
                    print("No extras in intent.")
                # --- End debug block ---
                EXTRA_TAG = autoclass('android.nfc.NfcAdapter').EXTRA_TAG
               
                tag = intent.getParcelableExtra(EXTRA_TAG)
                if tag:
                    print("NFC tag detected (regardless of action)!")
                    tag = cast('android.nfc.Tag', tag)
                    # tech_list = tag.getTechList() # Optional: log tech list if needed for debugging
                    # print("Tag technologies detected by Android:")
                    # for tech in tech_list:
                    #     print(f" - {tech}")

                    table_container = self.root.ids.home_screen.ids.table_container

                    def perform_nfc_transfer():
                        Clock.schedule_once(lambda dt: self.show_nfc_progress_dialog("Transferring data to NFC tag..."), 0.01)
                        # Schedule the actual NFC sending slightly after to allow dialog to show
                        Clock.schedule_once(lambda dt: self.send_csv_bitmap_via_nfc(intent), 0.05)

                    if table_container.children and hasattr(self, "manual_data_rows") and self.manual_data_rows:
                        print("Manual data input detected, adding manual data before NFC transfer.")
                        self.add_manual_data() # Call synchronously to ensure self.current_data is updated
                    
                    perform_nfc_transfer()
                    intent.setAction("") # Clear action to prevent re-handling by polling or resume
                    return

                # Fallback for specific NFC actions if EXTRA_TAG wasn't found but action indicates NFC
                # This might be redundant if EXTRA_TAG is always present for NFC events.
                elif action in [
                    "android.nfc.action.TAG_DISCOVERED",
                    "android.nfc.action.NDEF_DISCOVERED",
                    "android.nfc.action.TECH_DISCOVERED",
                ]:
                    print("NFC tag detected!")
                    # This path likely means EXTRA_TAG was null, which is unusual for these actions.
                    # Proceeding with send_csv_bitmap_via_nfc might fail if tag cannot be extracted by Java.
                    self.send_csv_bitmap_via_nfc(intent)
                    intent.setAction("") # Clear action
                    return

                # Handle shared data (SEND/VIEW)
                if action in ["android.intent.action.SEND", "android.intent.action.VIEW"]:
                    extras = intent.getExtras()
                    if extras and extras.containsKey("android.intent.extra.TEXT"):
                        # Handle shared text
                        shared_text = extras.getString("android.intent.extra.TEXT")
                        print(f"Received shared text: {shared_text}")
                        self.process_received_text(shared_text)
                    elif extras and extras.containsKey("android.intent.extra.STREAM"):
                        # Handle shared file
                        stream_uri = extras.getParcelable("android.intent.extra.STREAM")
                        print(f"Received stream URI: {stream_uri}")

                        # If the stream_uri is a string path, open it directly
                        if isinstance(stream_uri, str) and stream_uri.startswith("/"):
                            print(f"Received file path: {stream_uri}")
                            self.process_received_csv(stream_uri)
                        else:
                            Uri = autoclass('android.net.Uri')
                            try:
                                stream_uri = cast('android.net.Uri', stream_uri)
                            except Exception:
                                stream_uri = Uri.parse(str(stream_uri))

                            content_resolver = mActivity.getContentResolver()
                            file_path = self.resolve_uri_to_path(content_resolver, stream_uri)

                            if file_path:
                                self.process_received_csv(file_path)
                            else:
                                try:
                                    input_stream = content_resolver.openInputStream(stream_uri)
                                    if input_stream:
                                        ByteArrayOutputStream = autoclass('java.io.ByteArrayOutputStream')
                                        buffer = ByteArrayOutputStream()
                                        byte = input_stream.read()
                                        while byte != -1:
                                            buffer.write(byte)
                                            byte = input_stream.read()
                                        input_stream.close()
                                        content_bytes = bytes(buffer.toByteArray())
                                       
                                        try:
                                            content = content_bytes.decode("utf-8")
                                        except UnicodeDecodeError:
                                            print("UTF-8 decode failed, trying latin-1...")
                                            content = content_bytes.decode("latin-1")
                                        intent.setAction("") # Clear action after processing
                                        print(f"File contents (from InputStream):\n{content}")
                                        # Schedule the processing to ensure it runs on the Kivy main thread
                                        Clock.schedule_once(lambda dt, c=content: self.process_received_csv(c))
                                    else:
                                        print("InputStream is None. Cannot read the file.")
                                except Exception as e:
                                    print(f"Error reading from InputStream: {e}")
                else:
                    # If no specific data handled, but action was SEND/VIEW, clear it.
                    intent.setAction("") 
                    print("No valid data found in the intent.")
            except Exception as e:
                print(f"Error handling new intent: {e}")

    def resolve_uri_to_path(self, content_resolver, uri):
        """Resolve a content URI to a file path."""
        try:
            if uri is None:
                print("Error: URI is None. Cannot resolve path.")
                return None

            # Cast the Parcelable to a Uri
            Uri = autoclass('android.net.Uri')
            if not isinstance(uri, Uri):
                uri = Uri.parse(str(uri))  # Ensure it's a Uri object

            print(f"Resolving URI: {uri}")

            # Check if the URI has a valid scheme
            scheme = uri.getScheme()
            if scheme == "file":
                return uri.getPath()
            elif scheme == "content":
                # Query the content resolver for the file path
                projection = [autoclass("android.provider.MediaStore$MediaColumns").DATA]
                cursor = content_resolver.query(uri, projection, None, None, None)
                if cursor is not None:
                    column_index = cursor.getColumnIndexOrThrow(projection[0])
                    cursor.moveToFirst()
                    file_path = cursor.getString(column_index)
                    cursor.close()
                    return None
            else:
                print(f"Unsupported URI scheme: {scheme}")
                return None
        except Exception as e:
            print(f"Error resolving URI to path: {e}")
            return None

    def process_received_csv(self, file_path_or_uri):
        """Process the received CSV file or CSV text."""
        import io
         # Clear current data and table before loading new file
        self.current_data = []
        self.clear_table_data()
        try:
            # If it's CSV text (not a path or URI), parse directly
            if (
                    "\n" in file_path_or_uri or "\r" in file_path_or_uri
            ) and not file_path_or_uri.startswith("/") and not file_path_or_uri.startswith("content://"):
                # Looks like CSV text, not a path or URI
                csv_file = io.StringIO(file_path_or_uri)
                data = self.read_csv_to_dict(csv_file)
            else:
                # Fix for Android: prepend storage root if needed
                if file_path_or_uri.startswith("/Documents/"):
                    storage_root = "/storage/emulated/0"
                    abs_path = storage_root + file_path_or_uri
                    print(f"Trying absolute path: {abs_path}")
                    file_path_or_uri = abs_path

                if file_path_or_uri.startswith("/"):  # If it's a file path
                    with open(file_path_or_uri, mode="r", encoding="utf-8") as csv_file:
                        data = self.read_csv_to_dict(csv_file)
                else:  # If it's a content URI
                    content_resolver = mActivity.getContentResolver()
                    input_stream = content_resolver.openInputStream(file_path_or_uri)
                    content = input_stream.read().decode("utf-8")
                    csv_file = io.StringIO(content)
                    data = self.read_csv_to_dict(csv_file)

            self.current_data = data  # Store the data for filtering or other operations

            # Preprocess the data
            processed_data = self.preprocess_data(data)

            # Display the data as a table on the Home Screen
            self.display_table(processed_data)

            # Navigate to the Home Screen
            self.root.ids.screen_manager.current = "home"
            print(f"Processed received CSV: {file_path_or_uri}")
        except Exception as e:
            print(f"Error processing received CSV: {e}")

    def read_csv_from_assets(self, file_name):
        """Read a CSV file from the assets/CSV folder."""
        if is_android():
            try:
                # Get the Android context and AssetManager
                PythonActivity = autoclass('org.kivy.android.PythonActivity')
                context = PythonActivity.mActivity.getApplicationContext()
                AssetManager = autoclass('android.content.res.AssetManager')
                asset_manager = context.getAssets()

                # Open the file in the assets/CSV folder
                with asset_manager.open(f"CSV/{file_name}") as asset_file:
                    content = asset_file.read().decode("utf-8")
                    print(f"Content of {file_name}:\n{content}")
            except Exception as e:
                print(f"Error reading CSV from assets: {e}")
                return None
        else:
            # On non-Android platforms, read from the local assets/CSV folder
            file_path = os.path.join(os.path.dirname(__file__), "assets", "CSV", file_name)
            try:
                with open(file_path, "r", encoding="utf-8") as file:
                    content = file.read()
                    print(f"Content of {file_name}:\n{content}")
                    return content

            except Exception as e:
                print(f"Error reading CSV file: {e}")
                return None

    def copy_assets_to_internal_storage(self):
        """Copy the assets/CSV folder to the app's private storage directory."""
        private_storage_path = self.get_private_storage_path()
       
        if private_storage_path:
            try:
                csv_internal_path = os.path.join(private_storage_path, "CSV")

                # Ensure the destination directory exists
                if not os.path.exists(csv_internal_path):
                    os.makedirs(csv_internal_path)

                # Copy files and directories from assets/CSV to private storage
                if is_android():
                    AssetManager = autoclass('android.content.res.AssetManager')
                    context = mActivity.getApplicationContext()
                    asset_manager = context.getAssets()
                    files = asset_manager.list("CSV")  # List files and directories in the assets/CSV folder

                    for file_name in files:
                        source_path = f"CSV/{file_name}"
                        dest_path = os.path.join(csv_internal_path, file_name)

                        if asset_manager.list(source_path):  # Check if it's a directory
                            if not os.path.exists(dest_path):
                                os.makedirs(dest_path)  # Create the directory if it doesn't exist
                            # Recursively copy the directory
                            self.copy_directory_from_assets(asset_manager, source_path, dest_path)
                        else:
                            # Copy a single file
                            with asset_manager.open(source_path) as asset_file:
                                with open(dest_path, "wb") as output_file:
                                    output_file.write(asset_file.read())
                            print(f"Copied file: {source_path} to {dest_path}")
                else:
                    # Copy files locally for non-Android platforms
                    assets_csv_path = os.path.join(os.path.dirname(__file__), "assets", "CSV")
                    for file_name in os.listdir(assets_csv_path):
                        src_path = os.path.join(assets_csv_path, file_name)
                        dest_path = os.path.join(csv_internal_path, file_name)
                        if os.path.isdir(src_path):
                            if not os.path.exists(dest_path):
                                os.makedirs(dest_path)
                            # Recursively copy the directory
                            self.copy_directory_locally(src_path, dest_path)
                        else:
                            # Copy a single file
                            with open(src_path, "rb") as src, open(dest_path, "wb") as dest:
                                dest.write(src.read())
                            print(f"Copied file: {src_path} to {dest_path}")

                print(f"Assets copied to private storage: {csv_internal_path}")
                return csv_internal_path
            except Exception as e:
                print(f"Error copying assets to private storage: {e}")
                return None
        else:
            print("Private storage path is not available.")
            return None
    
    def delete_file_or_folder(self, path):
        """Delete the selected file or folder and refresh the file list."""
        try:
            base_dir = os.path.abspath(self.get_private_storage_path())
            abs_path = os.path.abspath(path)
            saved_cards_screen = self.root.ids.screen_manager.get_screen("saved_cards")

            # If deleting a folder or a non-csv file, always go to assets/CSV first
            if not abs_path.lower().endswith(".csv"):
                csv_root = self.ensure_csv_directory()
                self.populate_swipe_file_list()

            if os.path.exists(abs_path):
                if os.path.isdir(abs_path):
                    shutil.rmtree(abs_path)  # Recursively delete folder and contents
                    print(f"Deleted folder: {abs_path}")
                    toast("Folder deleted successfully.")
                else:
                    os.remove(abs_path)
                    print(f"Deleted file: {abs_path}")
                    toast("File deleted successfully.")

                # Refresh the swipe-to-delete file list
                self.populate_swipe_file_list()
                print("File and folder list refreshed.")

                self.clear_table_data()
                self.root.ids.screen_manager.current = "saved_cards"
            else:
                print(f"Path does not exist: {abs_path}")
        except Exception as e:
            print(f"Error deleting file or folder: {e}")

    def populate_swipe_file_list(self, target_dir=None, sort_by=None, reverse=None):
        saved_cards_screen = self.root.ids.screen_manager.get_screen("saved_cards")
        swipe_file_list = saved_cards_screen.ids.swipe_file_list
        swipe_file_list.clear_widgets()

        if target_dir is None:
            target_dir = self.ensure_csv_directory()

        # Add parent directory entry if not at root
        root_dir = self.ensure_csv_directory()
        if os.path.abspath(target_dir) != os.path.abspath(root_dir):
            parent_dir = os.path.abspath(os.path.join(target_dir, ".."))
            item = Builder.load_string(f'''
SwipeFileItem:
    file_path: r"{parent_dir}"
    icon: "arrow-left"
    file_size: ""
    display_name: "Back"
''')
            swipe_file_list.add_widget(item)

        entries = []
        for fname in os.listdir(target_dir):
            if fname.startswith('.'):
                continue  # Skip hidden files/folders
            # --- Filter by search_text ---
            if self.search_text and self.search_text not in fname.lower():
                continue
            fpath = os.path.abspath(os.path.join(target_dir, fname))
            is_dir = os.path.isdir(fpath)
            size = "" if is_dir else str(os.path.getsize(fpath))
            icon = "folder" if is_dir else "file"
            entries.append((fpath, icon, size, fname))

        # Sorting logic
        if sort_by == "name":
            entries.sort(key=lambda x: (x[1] != "folder", x[3].lower()), reverse=reverse)
        elif sort_by == "date":
            entries.sort(key=lambda x: (x[1] != "folder", os.path.getmtime(x[0])), reverse=reverse)
        elif sort_by == "type":
            entries.sort(key=lambda x: (x[1] != "folder", os.path.splitext(x[3])[1].lower(), x[3].lower()), reverse=reverse)

        for fpath, icon, size, fname in entries:
            item = Builder.load_string(f'''
SwipeFileItem:
    file_path: r"{fpath}"
    icon: "{icon}"
    file_size: "{size}"
''')
            swipe_file_list.add_widget(item)

    def show_manual_data_input(self):
        """Display manual data input fields in the CSV data table location based on filtered display options."""
        self.manual_data_fields = []  # <-- Add this line
        home_screen = self.root.ids.home_screen
        table_container = home_screen.ids.table_container

        # Clear any existing widgets in the table container
        table_container.clear_widgets()

               # Create a vertical layout to hold the rows and buttons
        main_layout = BoxLayout(orientation="vertical", spacing="10dp", size_hint=(1, None))
        main_layout.bind(minimum_height=main_layout.setter("height"))  # Adjust height dynamically

        # Define the available fields and their display options
        available_fields = {
            "Target": {"hint_text": "Target", "show": True},  # Always show Target
            "Range": {"hint_text": "Range", "show": show_range},  # Controlled by show_range
            "Elv": {"hint_text": "Elevation", "show": True},  # Always show Elevation
            "Wnd1": {"hint_text": "Wind 1", "show": True},  # Always show Wind 1
            "Wnd2": {"hint_text": "Wind 2", "show": show_2_wind_holds},  # Controlled by show_2_wind_holds
            "Lead": {"hint_text": "Lead", "show": show_lead},  # Controlled by show_lead
        }

        # Store the available fields for later use
        self.available_fields = available_fields

        # Add the first row of input fields
        self.add_data_row(main_layout)

        # Create a layout for the "ADD ROW" and "DELETE ROW" buttons
        add_row_layout = BoxLayout(
            orientation="horizontal",
            spacing="10dp",
            size_hint=(None, None),  # Not stretching horizontally
            height=dp(50),
        )
        add_row_layout.width = dp(260)  # 2 buttons * 120dp + 1 spacing * 10dp
        add_row_layout.pos_hint = {"center_x": 0.5}  # Center horizontally

        add_row_layout.add_widget(
            MDRaisedButton(
                text="ADD ROW",
                size_hint=(None, None),
                size=(dp(120), dp(40)),
                on_release=lambda x: self.add_data_row(main_layout)
            )
        )
        add_row_layout.add_widget(
            MDRaisedButton(
                text="DELETE ROW",
                size_hint=(None, None),
                size=(dp(120), dp(40)),
                md_bg_color=(1, 0, 0, 1),
                on_release=lambda x: self.delete_last_row(main_layout)
            )
        )

        # Create a layout for the "CANCEL" and "ADD" buttons
        action_buttons_layout = BoxLayout(orientation="horizontal", spacing="10dp", size_hint=(1, None), height=dp(50))

        # Add the button layouts to the main layout
        main_layout.add_widget(add_row_layout)
        main_layout.add_widget(action_buttons_layout)

        # Add the main layout to the table container
        table_container.add_widget(main_layout)

    def add_data_row(self, table_container):
        """Add a new row of data fields directly underneath the existing rows, with Next/Tab navigation."""
        row_layout = BoxLayout(orientation="horizontal", spacing="10dp", size_hint=(1, None))
        row_layout.height = dp(50)  # Adjust height for a single row of text fields

        row_fields = {}
        manual_fields = []
        for field_name, field_options in self.available_fields.items():
            if field_options["show"]:
                text_field = MDTextField(
                    hint_text=field_options["hint_text"],
                    multiline=False,
                    size_hint_x=0.15
                )
                row_fields[field_name] = text_field
                manual_fields.append(text_field)
                row_layout.add_widget(text_field)

        # Store the row fields for later use
        if not hasattr(self, "manual_data_rows"):
            self.manual_data_rows = []
        self.manual_data_rows.append(row_fields)

        # Store all manual fields in a flat list for navigation
        if not hasattr(self, "manual_data_fields"):
            self.manual_data_fields = []
        self.manual_data_fields.extend(manual_fields)

        # Find the correct index to insert the new row (above the button layouts)
        button_index = 0
        for i, child in enumerate(reversed(table_container.children)):
            if isinstance(child, BoxLayout) and any(
                isinstance(widget, MDRaisedButton) or isinstance(widget, MDFlatButton) for widget in child.children):
                button_index = len(table_container.children) - i
                break

        table_container.add_widget(row_layout, index=button_index)

        # Rebuild navigation for all homepage fields
        self.enable_next_navigation_on_homepage()

    def delete_last_row(self, table_container):
        """Delete the last row of data fields if there is more than one row and update navigation."""
        if len(table_container.children) <= 1:
            return  # Don't remove the last remaining row

        # Find the last added row (excluding button layouts)
        for child in reversed(table_container.children):
            if isinstance(child, BoxLayout) and not any(
                isinstance(widget, MDRaisedButton) or isinstance(widget, MDFlatButton) for widget in child.children):
                # Remove the MDTextFields in this row from manual_data_fields
                if hasattr(self, "manual_data_fields"):
                    for widget in child.children:
                        if isinstance(widget, MDTextField) and widget in self.manual_data_fields:
                            self.manual_data_fields.remove(widget)
                table_container.remove_widget(child)
                break

        # Also remove the last row_fields from manual_data_rows if present
        if hasattr(self, "manual_data_rows") and self.manual_data_rows:
            self.manual_data_rows.pop()

        # Rebuild navigation for all homepage fields
        self.enable_next_navigation_on_homepage()

    def enable_next_navigation_on_homepage(self):
        """Enable Next/Tab navigation for all MDTextField inputs on the homepage, including manual rows."""
        home_screen = self.root.ids.home_screen

        input_ids = [
            "stage_name_field",
            # Add more static fields here if needed, BEFORE manual data
        ]

        all_fields = []
        for field_id in input_ids:
            if hasattr(home_screen.ids, field_id):
                all_fields.append(home_screen.ids[field_id])

        if hasattr(self, "manual_data_fields"):
            all_fields.extend(self.manual_data_fields)
        if hasattr(home_screen.ids, "stage_notes_field"):
            all_fields.append(home_screen.ids.stage_notes_field)

        for i, tf in enumerate(all_fields):
            def make_on_text_validate(idx):
                def _on_text_validate(instance):
                    if idx + 1 < len(all_fields):
                        all_fields[idx + 1].focus = True
                return _on_text_validate
            tf.on_text_validate = make_on_text_validate(i)

            # Save the original handler
            orig_handler = getattr(tf, "_orig_keyboard_on_key_down", None)
            if orig_handler is None:
                tf._orig_keyboard_on_key_down = tf.keyboard_on_key_down

            def make_keyboard_on_key_down(idx, orig_handler):
                def _on_key_down(instance, *args):
                    # args: (keyboard, keycode, text, [modifiers])
                    if len(args) >= 3:
                        keycode = args[1]
                        if isinstance(keycode, (tuple, list)):
                            key_val = keycode[0]
                        else:
                            key_val = keycode
                        if key_val in (9, 40, 66):  # Tab, Enter, Next
                            if idx + 1 < len(all_fields):
                                all_fields[idx + 1].focus = True
                                return True
                    # For all other keys, call the original handler
                    return orig_handler(instance, *args)
                return _on_key_down
            tf.keyboard_on_key_down = make_keyboard_on_key_down(i, tf._orig_keyboard_on_key_down)
    
    def copy_directory_from_assets(self, asset_manager, source_path, dest_path):
        """Recursively copy a directory from the assets folder to the destination."""
        try:
            files = asset_manager.list(source_path)
            for file_name in files:
                sub_source_path = f"{source_path}/{file_name}"
                sub_dest_path = os.path.join(dest_path, file_name)

                if asset_manager.list(sub_source_path):  # Check if it's a directory
                    if not os.path.exists(sub_dest_path):
                        os.makedirs(sub_dest_path)
                    with asset_manager.open(sub_source_path) as asset_file:
                        with open(sub_dest_path, "wb") as output_file:
                            output_file.write(asset_file.read())
                    print(f"Copied file: {sub_source_path} to {sub_dest_path}")
        except Exception as e:
            print(f"Error copying directory from assets: {e}")

    def copy_directory_locally(self, src_path, dest_path):
        """Recursively copy a directory locally."""
        try:
            for file_name in os.listdir(src_path):
                sub_src_path = os.path.join(src_path, file_name)
                sub_dest_path = os.path.join(dest_path, file_name)

                if os.path.isdir(sub_src_path):
                    if not os.path.exists(sub_dest_path):
                        os.makedirs(sub_dest_path)
                    self.copy_directory_locally(sub_src_path, dest_path)
                else:
                    # Copy a single file
                    with open(sub_src_path, "rb") as src, open(sub_dest_path, "wb") as dest:
                        dest.write(src.read())
                    print(f"Copied file: {sub_src_path} to {dest_path}")
        except Exception as e:
            print(f"Error copying directory locally: {e}")

    def process_subject_content(self, subject_content):
        """Process the subject content received in the intent."""
        print(f"Processing subject content: {subject_content}")

        if subject_content == "Range Card":
            try:
                PythonActivity = autoclass('org.kivy.android.PythonActivity')
                intent = PythonActivity.mActivity.getIntent()

                # Try to get the URI from the intent's data
                stream_uri = intent.getData()
                if stream_uri is None:
                    # Fallback to extras if getData() doesn't work
                    extras = intent.getExtras()
                    if extras and extras.containsKey("android.intent.extra.STREAM"):
                        stream_uri = extras.getParcelable("android.intent.extra.STREAM")

                if stream_uri:
                    print(f"Received stream URI: {stream_uri}")

                    # Cast the Parcelable to a Uri
                    Uri = autoclass('android.net.Uri')
                    if not isinstance(stream_uri, Uri):
                        stream_uri = Uri.parse(str(stream_uri))  # Ensure it's a Uri object

                    # Resolve the URI to a file path or read from InputStream
                    content_resolver = mActivity.getContentResolver()
                    file_path = self.resolve_uri_to_path(content_resolver, stream_uri)

                    if file_path:
                        # Read and print the file contents
                        print(f"Resolved file path: {file_path}")
                        with open(file_path, "r", encoding="utf-8") as file:
                            content = file.read()
                            print(f"Contents of the file:\n{content}")
                    else:
                        # Fallback: Read directly from the InputStream
                        try:
                            input_stream = content_resolver.openInputStream(stream_uri)
                            if input_stream:
                                content = input_stream.read().decode("utf-8")
                                print(f"File contents (from InputStream):\n{content}")
                                self.process_received_text(content)
                            else:
                                print("InputStream is None. Cannot read the file.")
                        except Exception as e:
                            print(f"Error reading from InputStream: {e}")
                else:
                    print("No valid URI found in the intent.")
            except Exception as e:
                print(f"Error processing subject content: {e}")
                
    def hide_nfc_progress_dialog(self):
        if hasattr(self, "nfc_progress_dialog") and self.nfc_progress_dialog:
            self.nfc_progress_dialog.dismiss()
            self.nfc_progress_dialog = None
        # Nullify bar and label to ensure they are recreated fresh by show_nfc_progress_dialog
        # and to prevent callbacks from trying to update stale UI elements.
        if hasattr(self, "nfc_progress_bar"):
            self.nfc_progress_bar = None
        if hasattr(self, "nfc_progress_label"):
            self.nfc_progress_label = None
    def update_nfc_progress(self, percent):
        if hasattr(self, "nfc_progress_bar") and self.nfc_progress_bar:
            # If percent is 100, delay the update by 3 seconds
            if percent >= 100:
                Clock.schedule_once(lambda dt: self._finish_nfc_progress(), 3)
            else:
                self.nfc_progress_bar.value = percent

    def _finish_nfc_progress(self):
        if hasattr(self, "nfc_progress_bar") and self.nfc_progress_bar:
            self.nfc_progress_bar.value = 100
        if hasattr(self, "nfc_progress_label"):
            self.nfc_progress_label.text = "Transfer successful!"
            self.nfc_progress_label.color = (0, 0.6, 0, 1)
        Clock.schedule_once(lambda dt: self.hide_nfc_progress_dialog(), 2.5)
        Clock.schedule_once(lambda dt: self.clear_table_data())  # This will clear the table and show manual data input
        

    def hide_nfc_button(self):
        """Hide the NFC button if running on Android."""
        if is_android():
            try:
                # Assuming the NFC button has an ID like 'nfc_button'
                nfc_button = self.root.ids.home_screen.ids.nfc_button
                nfc_button.opacity = 0  # Make the button invisible
                nfc_button.disabled = True  # Disable the button
                print("NFC button hidden on Android.")
            except Exception as e:
                print(f"Error hiding NFC button: {e}")

    def verify_copied_files(self):
        """Verify the contents of the copied CSV files."""
        dest_dir = os.path.join(os.environ.get("ANDROID_PRIVATE", ""), "CSV")
        for file_name in os.listdir(dest_dir):
            dest_file = os.path.join(dest_dir, file_name)
            print(f"Verifying file: {dest_file}")
            with open(dest_file, "r", encoding="utf-8") as file:
                print(file.read())


def handle_received_file(intent):
    """Handle a file received via Intent.EXTRA_STREAM."""
    if is_android() and autoclass:
        try:
            # Import necessary Android classes
            Uri = autoclass('android.net.Uri')
            ContentResolver = autoclass('android.content.ContentResolver')

            # Check if the intent contains EXTRA_STREAM
            if intent.hasExtra(Intent.EXTRA_STREAM):
                # Get the Parcelable URI
                stream_uri = intent.getParcelableExtra(Intent.EXTRA_STREAM)
                print(f"Received Parcelable URI: {stream_uri}")

                # Cast the Parcelable to a Uri
                if not isinstance(stream_uri, Uri):
                    stream_uri = Uri.parse(str(stream_uri))  # Ensure it's a Uri object

                # Resolve the URI to a file path or read from InputStream
                content_resolver = mActivity.getContentResolver()
                file_path = MainApp().resolve_uri_to_path(content_resolver, stream_uri)

                if file_path:
                    # File path resolved, read the file

                    print(f"Resolved file path: {file_path}")
                    with open(file_path, "r", encoding="utf-8") as file:
                        content = file.read()
                        print(f"File contents:\n{content}")
                else:
                    # Fallback: Read directly from the InputStream
                    try:
                        input_stream = content_resolver.openInputStream(stream_uri)
                        if input_stream:
                            content = input_stream.read().decode("utf-8")
                            print(f"File contents (from InputStream):\n{content}")
                            self.process_received_text(content)
                        else:
                            print("InputStream is None. Cannot read the file.")

                    except Exception as e:
                        print(f"Error reading from InputStream: {e}")
            else:
                print("No EXTRA_STREAM found in the intent.")
        except Exception as e:
            print(f"Error handling received file: {e}")
    else:
        print("This functionality is only available on Android.")


def start_foreground_service(self):
    """Start a foreground service with a persistent notification."""
    if is_android():
        try:
            # Create a persistent notification
            notification.notify(
                title="Open E-Dope Service",
                message="The app is running in the background.",
                timeout=10  # Notification timeout in seconds
            )
            print("Foreground service started with a persistent notification.")
        except Exception as e:
            print(f"Error starting foreground service: {e}")
    else:
        print("Foreground service is only available on Android.")


def process_received_file(self, file_path):
    """Process the received file."""
    try:
        print(f"Processing received file: {file_path}")
        if file_path.endswith(".csv"):
            # Read and process the CSV file
            with open(file_path, "r", encoding="utf-8") as csv_file:
                data = self.read_csv_to_dict(csv_file)
                self.current_data = data
                self.display_table(data)
                print("CSV file processed successfully.")
        else:
            print("Unsupported file type.")
    except Exception as e:
        print(f"Error processing received file: {e}")


def process_received_text(self, text_data):
    """Process the received text data."""
    try:
        # Split the data into lines
        lines = text_data.strip().split("\n")
        # Extract the headers from the second line (after the metadata)
        headers = lines[1].split(",")
        # Parse the rows into dictionaries
        data = []
        for line in lines[2:]:  # Skip the first two lines (metadata and headers)
            row = line.split(",")
            data.append({headers[i]: row[i] for i in range(len(headers))})

        # Store the data for filtering or other operations
        self.current_data = data

        # Display the data in the table
        self.display_table(data)
        print("Text data processed and displayed successfully.")
    except Exception as e:
        print(f"Error processing text data: {e}")

s = MainApp.EPD_INIT_MAP["Good Display 3.7-inch"][0]
print("Length:", len(s))
for i, c in enumerate(s):
    if not c.isalnum():
        print(f"Non-alphanumeric at {i}: {repr(c)}")
for i in range(0, len(s), 40):
    print(f"{i:03d}: {s[i:i+40]}")

def pack_image_column_major(img):
    packed = bytearray()
    for x in range(width-1, -1, -1):  # right-to-left to match demo
        for y_block in range(0, height, 8):
            byte = 0
            for bit in range(8):
                y = y_block + bit
                if y >= height:
                    continue
                # In '1' mode, 0=black, 255=white
                if pixels[x, y] == 0:
                    byte |= (1 << (7 - bit))
            packed.append(byte)
    return bytes(packed)

if __name__ == "__main__":
    MainApp().run()