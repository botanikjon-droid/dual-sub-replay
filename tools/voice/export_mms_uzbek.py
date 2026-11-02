"""Export Meta's MMS Uzbek TTS (VITS) to one ONNX file the Android app can run offline.

Run in CI (needs internet for Hugging Face):
    python tools/voice/export_mms_uzbek.py out/

Writes to out/:
    uz-mms.onnx      input_ids int64 [1, T] -> waveform float32 [1, N] (16 kHz)
    vocab.json       character -> id, as the app's tokenizer reads it
    voice.json       sample rate, blank id, model id, licence note
    sample.wav       a test sentence, to listen to before installing

The model is CC-BY-NC 4.0 (non-commercial). It is a stand-in until a voice trained on the
owner's own recordings replaces it with the same input/output contract.
"""

import json
import sys
import wave
from pathlib import Path

import numpy as np
import onnxruntime as ort
import torch
from transformers import VitsModel, VitsTokenizer

MODEL_ID = "facebook/mms-tts-uzb-script_cyrillic"
TEST_TEXT = "Ўт пуфаги деворининг қалинлиги уч миллиметр, жигар ўлчамлари меъёрда."


class Waveform(torch.nn.Module):
    def __init__(self, model: VitsModel) -> None:
        super().__init__()
        self.model = model

    def forward(self, input_ids: torch.Tensor) -> torch.Tensor:
        return self.model(input_ids=input_ids).waveform


def write_wav(path: Path, samples: np.ndarray, rate: int) -> None:
    pcm = (np.clip(samples, -1.0, 1.0) * 32767).astype("<i2")
    with wave.open(str(path), "wb") as out:
        out.setnchannels(1)
        out.setsampwidth(2)
        out.setframerate(rate)
        out.writeframes(pcm.tobytes())


def ids_for(tokenizer: VitsTokenizer, text: str) -> np.ndarray:
    return tokenizer(text, return_tensors="np")["input_ids"].astype(np.int64)


def main(out_dir: str) -> None:
    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    tokenizer = VitsTokenizer.from_pretrained(MODEL_ID)
    model = VitsModel.from_pretrained(MODEL_ID).eval()
    rate = model.config.sampling_rate

    dummy = torch.tensor(ids_for(tokenizer, "салом дунё"))
    onnx_path = out / "uz-mms.onnx"
    with torch.no_grad():
        torch.onnx.export(
            Waveform(model),
            (dummy,),
            str(onnx_path),
            input_names=["input_ids"],
            output_names=["waveform"],
            dynamic_axes={"input_ids": {1: "tokens"}, "waveform": {1: "samples"}},
            opset_version=17,
            dynamo=False,
        )

    # The exported graph must follow the input length: a short and a long text give different lengths.
    session = ort.InferenceSession(str(onnx_path), providers=["CPUExecutionProvider"])
    short = session.run(None, {"input_ids": ids_for(tokenizer, "салом")})[0]
    long = session.run(None, {"input_ids": ids_for(tokenizer, TEST_TEXT)})[0]
    print("samples short/long:", short.shape, long.shape)
    if long.shape[1] <= short.shape[1] * 2:
        sys.exit("The exported model ignores the input length; dynamic shapes were lost.")
    write_wav(out / "sample.wav", long[0], rate)

    vocab = tokenizer.get_vocab()
    (out / "vocab.json").write_text(json.dumps(vocab, ensure_ascii=False, indent=1), encoding="utf-8")
    info = {
        "model": MODEL_ID,
        "sample_rate": rate,
        "add_blank": bool(tokenizer.add_blank),
        "blank_id": vocab.get(tokenizer.pad_token, 0),
        "script": "cyrillic",
        "license": "CC-BY-NC-4.0 (non-commercial)",
        "test_text": TEST_TEXT,
        "test_ids": ids_for(tokenizer, TEST_TEXT)[0].tolist(),
    }
    (out / "voice.json").write_text(json.dumps(info, ensure_ascii=False, indent=1), encoding="utf-8")
    print("size MB:", round(onnx_path.stat().st_size / 1e6, 1))


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "out")
