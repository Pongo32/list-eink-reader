# Test-only tools

## web-to-app

`web-to-app/` is a shallow checkout of [shiaho777/web-to-app](https://github.com/shiaho777/web-to-app), kept separate from the portfolio app and reserved for test/evaluation use. It is not a skill package and is not a runtime dependency of this project.

The checkout was verified at commit `5f91ac0cca4ea4a3b0da7ed4d6433a97b7d1920b`. Do not modify its upstream files as part of normal application work.
The parent repository ignores the checkout to avoid accidentally tracking a nested Git repository. To recreate it in a fresh workspace, run `git clone --depth 1 --branch main https://github.com/shiaho777/web-to-app.git test-tools/web-to-app`.

## Action log

Record actions and results involving test tools in [`actions.toon`](./actions.toon). Append events in TOON format, update the declared array length, and never write secrets or credentials to the log.
