# The voice environment

`tools/voiceover_render.py` renders a subtitle track as speech through **Chatterbox Multilingual**
(Resemble AI, MIT), run on the Mac GPU. It needs PyTorch, so it gets a Python environment of its
own here rather than the project's `.venv`:

```
/opt/homebrew/bin/python3.12 -m venv tools/voice/.venv
tools/voice/.venv/bin/pip install chatterbox-tts "setuptools<80"
```

`setuptools<80` is load-bearing: Chatterbox's watermarker (`resemble-perth`) imports
`pkg_resources`, which newer setuptools no longer ship, and without it the model constructor
fails with `'NoneType' object is not callable` on `PerthImplicitWatermarker`.

The model (about 3 GB) is fetched from Hugging Face into `~/.cache/huggingface` on the first run.
The environment is gitignored (`.venv/`), as is the output under `data/`.

```
tools/voice/.venv/bin/python tools/voiceover_render.py                     # the extended track
tools/voice/.venv/bin/python tools/voiceover_render.py --track default
tools/voice/.venv/bin/python tools/voiceover_render.py --engine say        # the Mac's Xander, instant
```
