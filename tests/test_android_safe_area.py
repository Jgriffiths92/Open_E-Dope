import ast
from pathlib import Path
from types import SimpleNamespace
import unittest


def load_safe_area_function():
    source = (Path(__file__).resolve().parents[1] / "main.py").read_text(
        encoding="utf-8"
    )
    module = ast.parse(source)
    function = next(
        node for node in module.body
        if isinstance(node, ast.FunctionDef) and node.name == "android_safe_area"
    )
    namespace = {}
    exec(compile(ast.Module(body=[function], type_ignores=[]), "main.py", "exec"), namespace)
    return namespace["android_safe_area"]


android_safe_area = load_safe_area_function()


def load_safe_area_methods(namespace):
    source = (Path(__file__).resolve().parents[1] / "main.py").read_text(
        encoding="utf-8"
    )
    module = ast.parse(source)
    app_class = next(
        node for node in module.body
        if isinstance(node, ast.ClassDef) and node.name == "MainApp"
    )
    methods = [
        node for node in app_class.body
        if isinstance(node, ast.FunctionDef)
        and node.name in {
            "read_android_safe_area", "set_android_safe_area",
            "update_android_safe_area",
        }
    ]
    app_class = ast.ClassDef(
        name="SafeAreaApp", bases=[], keywords=[], body=methods, decorator_list=[]
    )
    namespace["android_safe_area"] = android_safe_area
    exec(
        compile(ast.fix_missing_locations(ast.Module(body=[app_class], type_ignores=[])),
                "main.py", "exec"),
        namespace,
    )
    return namespace["SafeAreaApp"]


class AndroidSafeAreaTests(unittest.TestCase):
    def test_full_screen_surface_avoids_system_bars_and_cutout(self):
        self.assertEqual(
            android_safe_area(
                (1080, 2400), (0, 0, 1080, 2400), (0, 0, 1080, 2400),
                (10, 90, 20, 80),
            ),
            (10, 80, 1050, 2230),
        )

    def test_already_inset_surface_is_not_padded_twice(self):
        self.assertEqual(
            android_safe_area(
                (1080, 2230), (0, 0, 1080, 2400), (0, 90, 1080, 2230),
                (0, 90, 0, 80),
            ),
            (0, 0, 1080, 2230),
        )

    def test_partially_inset_scaled_surface(self):
        self.assertEqual(
            android_safe_area(
                (540, 1100), (0, 0, 1080, 2400), (0, 40, 1080, 2200),
                (0, 100, 0, 200),
            ),
            (0, 20, 540, 1050),
        )

    def test_invalid_surface_size_is_rejected(self):
        with self.assertRaises(ValueError):
            android_safe_area((100, 100), (0, 0, 100, 100), (0, 0, 0, 100), (0, 0, 0, 0))

    def test_android_geometry_updates_root_and_resizes(self):
        class View:
            def __init__(self, x, y, width, height):
                self.location = (x, y)
                self.width = width
                self.height = height

            def getLocationInWindow(self, result):
                result[:] = self.location

            def getWidth(self):
                return self.width

            def getHeight(self):
                return self.height

        surface = View(0, 40, 1080, 2200)
        decor = View(0, 0, 1080, 2400)
        insets = SimpleNamespace(left=0, top=100, right=0, bottom=200)
        decor.getRootWindowInsets = lambda: SimpleNamespace(getInsets=lambda types: insets)
        layout = SimpleNamespace(getChildAt=lambda index: surface)
        activity = SimpleNamespace(
            getWindow=lambda: SimpleNamespace(getDecorView=lambda: decor)
        )
        classes = {
            "org.libsdl.app.SDLActivity": SimpleNamespace(getContentView=lambda: layout),
            "android.view.WindowInsets$Type": SimpleNamespace(
                systemBars=lambda: 1, displayCutout=lambda: 2
            ),
        }
        window = SimpleNamespace(size=(540, 1100))
        app_type = load_safe_area_methods({
            "mActivity": activity,
            "autoclass": classes.__getitem__,
            "cast": lambda name, obj: obj,
            "Window": window,
            "Clock": SimpleNamespace(schedule_once=lambda callback, delay: callback(0)),
        })
        app = app_type()
        app.root = SimpleNamespace(size_hint=(1, 1))
        app._android_safe_area_data = None

        app.read_android_safe_area()
        self.assertEqual(app.root.size_hint, (None, None))
        self.assertEqual(app.root.pos, (0, 20))
        self.assertEqual(app.root.size, (540, 1050))

        window.size = (1080, 2200)
        app.update_android_safe_area(window, window.size)
        self.assertEqual(app.root.pos, (0, 40))
        self.assertEqual(app.root.size, (1080, 2100))


if __name__ == "__main__":
    unittest.main()
