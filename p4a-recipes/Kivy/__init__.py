import glob
import sys
from os.path import join, realpath

import packaging.version
import sh
from pythonforandroid.logger import shprint
from pythonforandroid.patching import will_build
from pythonforandroid.recipe import PyProjectRecipe
from pythonforandroid.toolchain import current_directory
from pythonforandroid.util import ensure_dir


def is_kivy_affected_by_deadlock_issue(recipe=None, arch=None):
    with current_directory(join(recipe.get_build_dir(arch.arch), "kivy")):
        kivy_version = shprint(
            sh.Command(sys.executable),
            "-c",
            "import _version; print(_version.__version__)",
        )
        return packaging.version.parse(str(kivy_version)) < packaging.version.Version(
            "2.2.0.dev0"
        )


class KivyRecipe(PyProjectRecipe):
    version = "2.3.1"
    url = "https://github.com/kivy/kivy/archive/{version}.zip"
    name = "kivy"
    depends = [("sdl2", "sdl3"), "pyjnius", "setuptools"]
    python_depends = ["certifi", "chardet", "idna", "requests", "urllib3", "filetype"]
    hostpython_prerequisites = []
    patches = [
        ("sdl-gl-swapwindow-nogil.patch", is_kivy_affected_by_deadlock_issue),
        "use_cython.patch",
    ]

    def get_recipe_dir(self):
        return join(self.ctx.root_dir, "recipes", "kivy")

    def get_recipe_env(self, arch, **kwargs):
        env = super().get_recipe_env(arch, **kwargs)
        env["LDFLAGS"] = env["LDFLAGS"] + " -L{} -L{} -L{}".format(
            self.ctx.get_libs_dir(arch.arch),
            self.ctx.libs_dir,
            join(self.ctx.bootstrap.build_dir, "obj", "local", arch.arch),
        )
        env["LDSHARED"] = env["CC"] + " -shared"
        env["LIBLINK"] = "NOTNONE"
        env["NDKPLATFORM"] = "NOTNONE"

        if "sdl2" in self.ctx.recipe_build_order:
            env["USE_SDL2"] = "1"
            env["KIVY_SPLIT_EXAMPLES"] = "1"
            mixer = self.get_recipe("sdl2_mixer", self.ctx)
            image = self.get_recipe("sdl2_image", self.ctx)
            env["KIVY_SDL2_PATH"] = ":".join(
                [
                    join(self.ctx.bootstrap.build_dir, "jni", "SDL", "include"),
                    *image.get_include_dirs(arch),
                    *mixer.get_include_dirs(arch),
                    join(self.ctx.bootstrap.build_dir, "jni", "SDL2_ttf"),
                ]
            )
        if "sdl3" in self.ctx.recipe_build_order:
            mixer = self.get_recipe("sdl3_mixer", self.ctx)
            image = self.get_recipe("sdl3_image", self.ctx)
            ttf = self.get_recipe("sdl3_ttf", self.ctx)
            sdl3 = self.get_recipe("sdl3", self.ctx)
            env["USE_SDL3"] = "1"
            env["KIVY_SPLIT_EXAMPLES"] = "1"
            env["KIVY_SDL3_PATH"] = ":".join(
                [
                    *mixer.get_include_dirs(arch),
                    *image.get_include_dirs(arch),
                    *ttf.get_include_dirs(arch),
                    *sdl3.get_include_dirs(arch),
                ]
            )
        return env

    def build_arch(self, arch):
        self.install_hostpython_prerequisites(
            packages=["build[virtualenv]", "pip"] + self.hostpython_prerequisites
        )
        build_dir = self.get_build_dir(arch.arch)
        env = self.get_recipe_env(arch, with_flags_in_cc=True)
        sub_build_dir = join(build_dir, "p4a_android_build")
        ensure_dir(sub_build_dir)

        # Preserve the expected executable path for later recipes.
        shprint(
            sh.cp,
            self.real_hostpython_location,
            self.ctx.python_recipe.get_android_python_exe(arch),
        )

        build_args = [
            "-m",
            "build",
            "--wheel",
            "--config-setting",
            "builddir={}".format(sub_build_dir),
        ] + self.extra_build_args

        with current_directory(build_dir):
            shprint(
                sh.Command(self.real_hostpython_location),
                *build_args,
                _env=env,
            )
            built_wheels = [realpath(whl) for whl in glob.glob("dist/*.whl")]
        self.install_wheel(arch, built_wheels)


recipe = KivyRecipe()
