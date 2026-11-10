"""./glass: puts Glass on an Android TV, keeps it updated, checks it, and takes it off again."""
import argparse
import os
import sys

HELP = """\
Glass for Android TV

  ./glass                 set up a TV (the first time), or choose what to do
  ./glass setup           put Glass on a TV: tools, finding the TV, build, install, home screen
  ./glass update          build the newest Glass and put it on the TV
  ./glass status          how Glass and the TV are doing
  ./glass doctor          find what's off and fix it
  ./glass restore         remove Glass and bring back the TV's own home
  ./glass demo [what]     the interface with nothing real happening (setup, update, status, doctor, restore, fail)

  --tv ADDRESS            the TV's address (192.168.1.20), instead of finding it
  --yes, -y               take the recommended answer to every question
  --plain                 plain lines instead of the full-screen view (automatic without a terminal)
  --no-color              no colour

Logs go to {logs}; nothing is printed while it works.
"""


def main(argv=None):
    from . import paths
    p = argparse.ArgumentParser(prog="./glass", add_help=False)
    p.add_argument("command", nargs="?", default=None)
    p.add_argument("what", nargs="?", default=None)
    p.add_argument("--tv")
    p.add_argument("--yes", "-y", action="store_true")
    p.add_argument("--plain", action="store_true")
    p.add_argument("--no-color", action="store_true")
    p.add_argument("--auto", type=float, default=None, help=argparse.SUPPRESS)
    p.add_argument("--help", "-h", action="store_true")
    p.add_argument("--version", action="store_true")
    opts = p.parse_args(argv)

    if opts.help or opts.command in ("help",):
        sys.stdout.write(HELP.format(logs=paths.short(paths.LOGS)))
        return 0
    from . import build as bld
    if opts.version or opts.command == "version":
        rel, name, code = bld.version()
        sys.stdout.write(("Glass %s (versionCode %d)\n" % (rel, code)) if name == rel
                         else ("Glass %s (%s, versionCode %d)\n" % (rel, name, code)))
        return 0
    if opts.no_color:
        os.environ["NO_COLOR"] = "1"

    from . import flows, demo
    from .app import Plain, Screen

    commands = {"setup": flows.setup, "update": flows.update, "status": flows.status, "doctor": flows.doctor,
                "restore": flows.restore}
    cmd = opts.command
    if cmd not in (None, "demo") and cmd not in commands:
        sys.stderr.write("./glass doesn't know \"%s\". ./glass help lists what it does.\n" % cmd)
        return 2

    full = (sys.stdin.isatty() and sys.stdout.isatty() and not opts.plain and os.environ.get("TERM") != "dumb"
            and os.name == "posix")
    log_name = os.environ.get("GLASS_DEMO_LOGNAME", "demo") if cmd == "demo" else (cmd or "glass")
    ui = (Screen if full else Plain)(log_name, assume_yes=opts.yes)

    if cmd == "demo":
        what = opts.what or "setup"
        fail = "build" if what == "fail" else None
        fn = demo.FLOWS.get("setup" if what == "fail" else what)
        if not fn:
            sys.stderr.write("./glass demo setup|update|status|doctor|restore|fail\n")
            return 2
        if full and opts.auto is not None:
            ui.auto = opts.auto
        if fail:
            return ui.play(lambda u: fn(u, opts, fail=fail))
        return ui.play(lambda u: fn(u, opts))

    opts.command = cmd
    if cmd is None:
        from . import tv as tvmod
        if not tvmod.saved_address():
            opts.command = "setup"
            return ui.play(lambda u: flows.setup(u, opts))
        if not full:
            sys.stdout.write(HELP.format(logs=paths.short(paths.LOGS)))
            return 0

        def chooser(u):
            nxt = flows.menu(u, opts)
            if nxt:
                opts.command = nxt
                commands[nxt](u, opts)
        return ui.play(chooser)
    return ui.play(lambda u: commands[cmd](u, opts))


if __name__ == "__main__":
    sys.exit(main())
