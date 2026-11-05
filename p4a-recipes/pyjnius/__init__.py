import glob
from os.path import join, realpath

import sh
from pythonforandroid.logger import shprint
from pythonforandroid.patching import will_build
from pythonforandroid.recipe import PyProjectRecipe
from pythonforandroid.toolchain import current_directory, info
from pythonforandroid.util import ensure_dir


class PyjniusRecipe(PyProjectRecipe):
    version = "1.6.1"
    url = "https://github.com/kivy/pyjnius/archive/{version}.zip"
    name = "pyjnius"
    depends = [("genericndkbuild", "sdl2", "sdl3"), "six"]
    site_packages_name = "jnius"
    patches = [
        "use_cython.patch",
        "cython_version_pin.patch",
        ("genericndkbuild_jnienv_getter.patch", will_build("genericndkbuild")),
        ("sdl3_jnienv_getter.patch", will_build("sdl3")),
    ]

    def get_recipe_dir(self):
        return join(self.ctx.root_dir, "recipes", "pyjnius")

    def get_recipe_env(self, arch, **kwargs):
        env = super().get_recipe_env(arch, **kwargs)
        env["LDFLAGS"] += " -L{} -L{} -L{}".format(
            self.ctx.get_libs_dir(arch.arch),
            self.ctx.libs_dir,
            join(self.ctx.bootstrap.build_dir, "obj", "local", arch.arch),
        )
        env["LDSHARED"] = env["CC"] + " -shared"
        env["LIBLINK"] = "NOTNONE"
        env["NDKPLATFORM"] = "NOTNONE"
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
        shprint(sh.cp, self.real_hostpython_location, self.ctx.python_recipe.python_exe)

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

    def postbuild_arch(self, arch):
        super().postbuild_arch(arch)
        info("Copying pyjnius java class to classes build dir")
        with current_directory(self.get_build_dir(arch.arch)):
            shprint(sh.cp, "-a", join("jnius", "src", "org"), self.ctx.javaclass_dir)


recipe = PyjniusRecipe()
