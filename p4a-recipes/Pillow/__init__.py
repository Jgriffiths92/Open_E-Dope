import glob
from os.path import join, realpath

import sh
from pythonforandroid.logger import shprint
from pythonforandroid.recipe import PyProjectRecipe
from pythonforandroid.util import current_directory, ensure_dir


class PillowRecipe(PyProjectRecipe):
    version = "10.3.0"
    url = "https://github.com/python-pillow/Pillow/archive/{version}.tar.gz"
    site_packages_name = "PIL"
    patches = ["setup.py.patch"]
    depends = ["png", "jpeg", "freetype", "setuptools"]
    opt_depends = ["libwebp"]

    def get_recipe_env(self, arch, **kwargs):
        env = super().get_recipe_env(arch, **kwargs)

        jpeg = self.get_recipe("jpeg", self.ctx)
        jpeg_dir = jpeg.get_build_dir(arch.arch)
        env["JPEG_ROOT"] = "{}:{}".format(jpeg_dir, jpeg_dir)

        freetype = self.get_recipe("freetype", self.ctx)
        freetype_dir = freetype.get_build_dir(arch.arch)
        env["FREETYPE_ROOT"] = "{}:{}".format(
            join(freetype_dir, "objs", ".libs"),
            join(freetype_dir, "include"),
        )

        harfbuzz = self.get_recipe("harfbuzz", self.ctx)
        harfbuzz_dir = harfbuzz.get_build_dir(arch.arch)
        env["HARFBUZZ_ROOT"] = "{}:{}".format(
            join(harfbuzz_dir, "src", ".libs"),
            harfbuzz_dir,
        )
        env["ZLIB_ROOT"] = (
            f"{arch.ndk_lib_dir_versioned}:{self.ctx.ndk.sysroot_include_dir}"
        )

        if "libwebp" in self.ctx.recipe_build_order:
            webp = self.get_recipe("libwebp", self.ctx)
            webp_dir = join(webp.get_build_dir(arch.arch), "installation")
            env["WEBP_ROOT"] = "{}:{}".format(
                join(webp_dir, "lib"),
                join(webp_dir, "include"),
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


recipe = PillowRecipe()
