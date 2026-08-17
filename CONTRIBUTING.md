# Contributing to Gravitee Singularitee

You think Gravitee Singularitee is awesome, and you'd like to contribute?
Here are some guidelines that should help you get started.

## Bootstrap your dev environment

### Prerequisites

You will need the following tools installed on your computer:

* **Java 25** (`.java-version` pins `25.0.4`) — the FFM-based engines need `--enable-preview`
* **Maven**
* Optional: [go-task](https://taskfile.dev) (`brew install go-task`) and [uv](https://docs.astral.sh/uv/) for the demo tasks and scripts

No Docker is needed to build or test — the suite is self-contained. Running the **vLLM**
engine additionally needs a Python virtualenv (see [Running vLLM locally](#running-vllm-locally));
llama.cpp, ONNX and GLiNER need nothing beyond the build.

Supported hosts are the two [llamaj.cpp](https://github.com/gravitee-io/llamaj.cpp) ships bindings
for: **macOS on Apple Silicon** and **Linux on x86_64**.

### Get the project and prepare your workspace

Clone the project in your workspace:

```bash
git clone https://github.com/gravitee-io/gravitee-singularitee
```

The fastest path from a fresh clone to a running server:

```bash
./install.sh
```

This checks prerequisites, downloads the llama.cpp native libraries into `~/.llama.cpp` (they are
**not** bundled in the jar), builds the distribution and starts the server.

To build by hand:

```bash
mvn clean install
```

The build **validates formatting (prettier-java) and Apache-2.0 license headers**, and a violation
fails it before compiling. Fix both in place with:

```bash
mvn prettier:write license:format
```

If you want to skip validation and tests while iterating:

```bash
mvn clean install -DskipTests -Dskip.validation=true
```

Each build assembles a full distribution under
`gravitee-singularitee-standalone/gravitee-singularitee-standalone-distribution/target/distribution/`
(`bin/`, `config/`, `lib/`, `lib/ext/`, `plugins/`). That folder is what `gravitee.home` points at.

### Run configuration

Every file under [`examples/`](examples/README.md) is a complete, runnable workspace:

```bash
./run-server.sh --list                                      # every runnable workspace
./run-server.sh --workspace examples/llama/qwen3-0.6b.yaml  # small and fast
./run-server.sh --debug                                     # TRACE-log rendered prompts
```

Model weights download from HuggingFace on first start into
`~/.cache/gravitee-singularitee/models`, deliberately outside the build tree so `mvn clean` does
not wipe multi-GB files. Export `HF_TOKEN` for gated repos.

`task` wraps the common paths (`task run:qwen`, `task run:guard`, `task chat`, `task classify`).
IntelliJ run configurations are committed under `.run/`.

See [Getting Started](docs/getting-started/README.md) for the full configuration reference, and
[CLAUDE.md](CLAUDE.md) for the project layout, extension points and gotchas.

### Running vLLM locally

The llama.cpp, ONNX and GLiNER engines need nothing beyond the build. **vLLM does**: it runs
from a Python virtualenv that the JVM loads CPython out of, so it is opt-in and skipped by
default (`vllm.setupVenv.skip=true`).

```bash
./scripts/setup-venv.sh -b metal      # Apple Silicon; also: -b cuda | -b cpu
                                      # -d <dir> (default ~/.venv-gravitee-ai), -v 3.12
```

`run-server.sh` detects a vLLM workspace and points the JVM at
`~/.venv-gravitee-ai/.venv` automatically — pass `--venv` or set `VLLM_VENV` for another
location. Only `-Dvllm4j.venv` is read by vLLM4j itself, so a venv on `PATH` alone is not enough.

A Maven backend profile can bootstrap the venv during `initialize` instead:

```bash
mvn verify -Pvllm-integration,metal                              # creates the venv, then runs
mvn verify -Pvllm-integration -Dvllm.venv.path=/path/to/.venv    # reuse an existing one
```

Caveats worth knowing before you file a bug against it:

- **vLLM is Linux/CUDA-first.** `examples/vllm/*.yaml` are sized for an 80 GB card; on Apple
  Silicon use `examples/vllm/gpt-oss-20b-mac.yaml`, which shrinks the context window, KV and
  `gpu_memory_utilization` (Metal applies it to *total* unified memory, not free memory).
- **Keep the venv's vLLM version in step.** `setup-venv.sh` pins vLLM `0.26.0`, matching
  `Dockerfile.vllm-cuda`'s `VLLM_IMAGE`; vLLM4j is compiled against a specific vLLM Python API.
- The `examples/vllm/` set is far less exercised than llama.cpp — see the note in
  [`examples/README.md`](examples/README.md) for what has actually been run end to end.

### Tests

```bash
mvn test                  # full suite
task test:examples        # loads every examples/**.yaml through the real workspace loader
```

`task test:examples` is the fastest guard against breaking a workspace — run it after touching
anything under `examples/` or the loader. Note it validates *structure* only: it never resolves
model weights, so a wrong HuggingFace repo name still passes there and fails at boot.

CI builds with `-DskipTests`, so **run the tests locally** — nothing downstream will catch you.

## Working with GitHub issues

We use GitHub issues to track bugs and enhancements. Found a bug in the source code? Want to
propose new features or enhancements? You can help us by submitting an issue in our
[repository](https://github.com/gravitee-io/issues/issues).
Before submitting your issue, please search the
[issues archive](https://github.com/gravitee-io/issues/issues) to see if your question has already
been answered.

Providing the following information will help us deal quickly with your issue:

* **Overview of the issue**: describe the issue and why this is a bug for you.
* **Version(s)**: possible regression?
* **Environment**: OS and architecture, GPU/driver if relevant, and the engine involved (llama.cpp, vLLM, ONNX, GLiNER).
* **The workspace**: the `examples/` file used, or the relevant part of your own workspace YAML.
* **Do you have a stack trace, logs, screenshots?** Add these to the issue's description. `./run-server.sh --debug` logs the prompt exactly as the model received it, which is usually the missing piece.

## Submitting changes

Have you submitted an issue to the project and know how to fix it? You can contribute to the
project by [forking the repository](https://guides.github.com/activities/forking/) and
[submitting your pull requests](https://guides.github.com/activities/forking/#making-a-pull-request).

Before you submit your pull request consider the following guidelines:

* Make your changes in a new git branch:

```shell
git checkout -b issue/<issue-id>-my-fix-branch main
```

Note: `issue-id` references the id generated by GitHub.

* Create your patch, **including appropriate test cases**.
* Update the documentation if you create new features or think the documentation needs to be
  updated/completed. Docs live in [`docs/`](docs/README.md), one page per capability; step types
  and the workspace schema are in [ARCHITECTURE.md](ARCHITECTURE.md).
* Commit your changes using a descriptive
  [Conventional Commit Message](https://conventionalcommits.org/).

```shell
git commit -a -m "feat: this is an example"
```

* Build your changes locally to **ensure all the tests pass**:

```shell
mvn clean install
```

* Push your branch to GitHub:

```shell
git push origin issue/<issue-id>-my-fix-branch
```

* In GitHub, send a pull request to `gravitee-io/gravitee-singularitee:main`.

* If we suggest changes then:
  * Make the required updates.
  * Re-run the test suite to ensure tests are still passing.
  * Commit your changes to your branch (e.g. `issue/<issue-id>-my-fix-branch`).
  * Push the changes to your GitHub repository (this will update your Pull Request).

If the PR gets too outdated we may ask you to rebase and force push to update the PR:

```shell
git rebase main
git push origin issue/<issue-id>-my-fix-branch -f
```

That's it! You've just contributed to the project, and we really appreciate it!

## Further information

You can find more detailed information about contributing in the
[GitHub guides](https://guides.github.com/activities/contributing-to-open-source/).
